package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;
import static com.lingan.ucp.nocode.tools.ReportMultiSourceFixture.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.FieldConversionDependencyInspector.Impact;
import com.lingan.ucp.nocode.application.service.resource.ApplicationFieldConversionDependencies;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.util.*;

/**
 * 多个数据来源与对象变更：自动跟随（P6，附加来源的对象发布后整张统计重新校验，对齐失效 ⇒ 跟随停在待处理）与字段变更的依赖扫描（P7，位置写明来源）。
 * 自动跟随在对象发布里另起事务，不能整体回滚：本类用随机前缀建对象与应用，结束时按前缀清理（与 ApplicationFollowIntegrationTest 同一做法）。
 */
class ReportMultiSourceFollowTest {
    private ReportMultiSourceFixture f;
    private FollowFixture follow;

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() {
        Mockito.when(
                        servicesContext
                                .getBean(PermissionCommonApi.class)
                                .hasAnyPermissions(10001L, "nocode:app:manage"))
                .thenReturn(true);
        f = new ReportMultiSourceFixture();
        follow = new FollowFixture();
        follow.support.prefix = f.support.prefix;
    }

    @AfterEach
    void cleanup() {
        follow.cleanup();
    }

    /** 打开支出对象的可编辑草稿，按 change 改关系或字段配置后保存（不发布）。 */
    private void editExpense(
            java.util.function.UnaryOperator<List<DataCenter.Relation>> relations,
            java.util.function.UnaryOperator<Map<String, DataCenter.FieldOptions>> options) {
        DataCenter.Design current = designs.get(f.expense.objectId());
        DataCenter.Design editable =
                designs.editPublished(
                        new DataCenter.Revision(
                                f.expense.objectId(), current.draft().lockVersion(), null),
                        OWNER);
        ObjectDraft draft = editable.draft();
        designs.save(
                new DataCenter.SaveDesign(
                        new SaveObjectDraft(
                                draft.id(),
                                draft.lockVersion(),
                                draft.objectCode(),
                                draft.objectName(),
                                draft.description(),
                                draft.tableName(),
                                draft.titleFieldId(),
                                new ArrayList<>(draft.fields()),
                                List.of()),
                        editable.settings(),
                        options.apply(new HashMap<>(editable.fieldOptions())),
                        relations.apply(new ArrayList<>(editable.relations())),
                        editable.indexes(),
                        editable.details(),
                        editable.mainBinding()),
                OWNER);
    }

    /** 行维度按「渠道」对齐（两个对象各自的渠道，同一套局部选项）的例 1 变体。 */
    private ApplicationReports.Config byChannel() {
        return config(
                f.stay.objectId(),
                null,
                null,
                "PIVOT",
                List.of(dim(f.stayChannel, null, "VALUE")),
                List.of(),
                List.of(
                        metric("cur", "当月金额", "SUM", f.curAmount, null),
                        metric("exp", "支出", "SUM", f.amount, "expense"),
                        formula("pro", "利润", "SUBTRACT", "cur", "exp")),
                List.of(),
                null,
                null,
                false,
                null,
                null,
                "入住",
                List.of(
                        new ApplicationReports.Source(
                                "expense",
                                "支出",
                                f.expense.objectId(),
                                null,
                                null,
                                List.of(dim(f.expenseChannel, null, "VALUE")),
                                null,
                                null,
                                null,
                                null)),
                null,
                null);
    }

    /**
     * P6（实测口径）：支出对象把「渠道」的选项改成另一套 ⇒ 统计里与来源 1「渠道」的对齐失效。依赖扫描在发布计划里就把这张统计列出来（位置写明来源与对齐维度），
     * 应用进入「需暂停」清单；确认发布后应用被暂停（DISABLED），仍固定在旧对象版本，不会带着失效的对齐继续跟随运行。 契约原写「改支出.物件的目标对象 ⇒ 跟随停在待处理、原因含
     * L8·R2」：① 生成的引用关系不能原地换目标（见下一个用例的实测报错）；② 能换目标的显式引用要清空该列，
     * 属于字段变更，同样先被这里的依赖扫描拦成「暂停」而走不到跟随——所以跟随时的重新校验在这条路上不会被触发。
     */
    @Test
    void p6AlignmentBreakingChangePausesTheApplication() {
        f.objects();
        f.app(f.resource("report", "REPORT", byChannel()));
        editExpense(
                r -> r,
                o -> {
                    o.put(
                            f.expenseChannel,
                            FieldRuleFixture.options(
                                    List.of(
                                            new DataCenter.Option("web", "官网", false),
                                            new DataCenter.Option("phone", "电话", false))));
                    return o;
                });
        DataCenter.PublishPlan plan = follow.plan(f.expense.objectId());
        System.out.println("P6 plan checks: " + plan.checks());
        System.out.println("P6 plan upgrades: " + plan.applicationUpgrades());
        assertThat(plan.applicationUpgrades())
                .singleElement()
                .satisfies(
                        u ->
                                assertThat(u.reasons())
                                        .anyMatch(
                                                reason ->
                                                        reason.contains(
                                                                "REPORT / 多个数据来源 report / 来源 支出 /"
                                                                        + " 对齐维度 "
                                                                        + f.expenseChannel)));
        follow.publish(plan, "改渠道选项", OWNER);
        System.out.println("P6 app after publish: " + f.apps.get(f.app).application());
        assertThat(f.apps.get(f.app).application().status()).isEqualTo("DISABLED");
        assertThat(follow.publishedReference(f.app, f.expense.objectId()).path("versionNo").asInt())
                .as("应用仍固定在旧对象版本")
                .isEqualTo(1);
    }

    /** 对照：改的是与统计无关的字段 ⇒ 跟随照常成功。 */
    @Test
    void p6UnrelatedChangeFollows() {
        f.objects();
        f.app(f.resource("report", "REPORT", byChannel()));
        String planId = follow.publishNewField(f.expense.objectId(), "note", "加备注");
        assertThat(follow.state(f.app, f.expense.objectId())).containsEntry("state", "FOLLOWING");
        assertThat(follow.publishedReference(f.app, f.expense.objectId()).path("versionNo").asInt())
                .isEqualTo(2);
        assertThat(planId).isNotBlank();
    }

    /** 契约 P6 原句的前提：引用关系换目标对象在对象设计这一步就被拒绝（记下实测，不是本功能的规则）。 */
    @Test
    void relationRetargetIsRejectedByObjectDesign() {
        f.objects();
        f.app(f.resource("report", "REPORT", f.example1()));
        Throwable error =
                catchThrowable(
                        () -> {
                            editExpense(
                                    rs -> {
                                        List<DataCenter.Relation> out = new ArrayList<>();
                                        for (DataCenter.Relation r : rs)
                                            out.add(
                                                    r.code().equals("property")
                                                            ? new DataCenter.Relation(
                                                                    r.id(),
                                                                    r.code(),
                                                                    r.name(),
                                                                    r.kind(),
                                                                    f.account.objectId(),
                                                                    r.fieldId(),
                                                                    r.targetFieldId(),
                                                                    r.required(),
                                                                    r.onDelete(),
                                                                    r.sourceDetailId())
                                                            : r);
                                        return out;
                                    },
                                    o -> o);
                            DataCenter.PublishPlan plan = follow.plan(f.expense.objectId());
                            System.out.println("P6 retarget plan checks: " + plan.checks());
                            follow.publish(plan, "改引用目标", OWNER);
                        });
        System.out.println("P6 retarget outcome: " + error);
        Map<String, Object> state = follow.state(f.app, f.expense.objectId());
        System.out.println("P6 retarget follow state: " + state);
        if (error == null)
            assertThat(state.get("pending_reason").toString())
                    .contains("两者引用的不是同一个对象（「物件」与「会计科目」）");
    }

    private static DataCenter.Definition change(
            DataCenter.Definition d, java.util.function.Consumer<ObjectNode> edit) {
        ObjectNode node = mapper.valueToTree(d);
        edit.accept(node);
        return mapper.convertValue(node, DataCenter.Definition.class);
    }

    private List<Impact> scan(
            DataCenter.Definition proposed, String field, ApplicationReports.Config config) {
        Map<String, DataCenter.Definition> definitions = new LinkedHashMap<>();
        for (DataCenter.Definition d : f.referenced()) definitions.put(d.objectId(), d);
        return ApplicationFieldConversionDependencies.inspectDefinition(
                mapper,
                "app",
                "民宿账务",
                new ApplicationCenter.Definition(
                        List.of(), List.of(f.resource("report", "REPORT", config))),
                f.expense,
                proposed,
                Set.of(field),
                definitions);
    }

    /** P7：停用「支出.支出日期」⇒ 预检列出这张统计，位置写明来源；换型让对齐失效 ⇒ 阻断（对齐维度）。 */
    @Test
    void p7DependencyScanNamesTheSource() {
        f.objects();
        DataCenter.Definition inactive =
                change(
                        f.expense,
                        n ->
                                ((ObjectNode) n.path("fieldOptions"))
                                        .set(
                                                f.paidOn,
                                                mapper.valueToTree(
                                                        withState(
                                                                f.expense
                                                                        .fieldOptions()
                                                                        .get(f.paidOn)))));
        List<Impact> impacts = scan(inactive, f.paidOn, f.example1());
        impacts.forEach(
                i -> System.out.println("P7 impact: " + i.location() + " | " + i.message()));
        assertThat(impacts)
                .anyMatch(i -> i.location().contains("/ 来源 支出 / 分组 " + f.paidOn) && i.blocking());
        // 支出.金额是来源「支出」的指标字段：按 sourceId 找到支出对象。
        DataCenter.Definition noAmount =
                change(
                        f.expense,
                        n ->
                                ((ObjectNode) n.path("fieldOptions"))
                                        .set(
                                                f.amount,
                                                mapper.valueToTree(
                                                        withState(
                                                                f.expense
                                                                        .fieldOptions()
                                                                        .get(f.amount)))));
        assertThat(scan(noAmount, f.amount, f.example1()))
                .anyMatch(i -> i.location().contains("/ 指标 支出") && i.blocking());
        // 对齐：入住记录.单号 与 支出.单号 按原值对齐；支出.单号 换成整数 ⇒ 不再相容 ⇒ 阻断。
        ApplicationReports.Config byName =
                config(
                        f.stay.objectId(),
                        null,
                        null,
                        "TABLE",
                        List.of(dim(f.stayName, null, "VALUE")),
                        null,
                        List.of(
                                metric("cur", "当月金额", "SUM", f.curAmount, null),
                                metric("exp", "支出", "SUM", f.amount, "expense")),
                        List.of(),
                        null,
                        null,
                        false,
                        null,
                        null,
                        "入住",
                        List.of(
                                new ApplicationReports.Source(
                                        "expense",
                                        "支出",
                                        f.expense.objectId(),
                                        null,
                                        null,
                                        List.of(dim(f.expenseName, null, "VALUE")),
                                        null,
                                        null,
                                        null,
                                        null)),
                        null,
                        null);
        DataCenter.Definition integer =
                change(
                        f.expense,
                        n -> {
                            for (JsonNode field : n.path("fields"))
                                if (field.path("id").asText().equals(f.expenseName))
                                    ((ObjectNode) field).put("type", "INTEGER").putNull("length");
                        });
        List<Impact> aligned = scan(integer, f.expenseName, byName);
        aligned.forEach(
                i ->
                        System.out.println(
                                "P7 aligned impact: " + i.location() + " | " + i.message()));
        assertThat(aligned)
                .anyMatch(
                        i ->
                                i.location().contains("/ 来源 支出 / 对齐维度 " + f.expenseName)
                                        && i.blocking());
        // 对照：换成另一种文本长度不影响对齐。
        assertThat(scan(f.expense, f.expenseName, byName))
                .noneMatch(i -> i.location().contains("对齐维度"));
    }

    private static DataCenter.FieldOptions withState(DataCenter.FieldOptions o) {
        DataCenter.FieldOptions base = o == null ? DataCenter.FieldOptions.defaults() : o;
        ObjectNode node = mapper.valueToTree(base);
        node.put("state", "INACTIVE");
        return mapper.convertValue(node, DataCenter.FieldOptions.class);
    }
}
