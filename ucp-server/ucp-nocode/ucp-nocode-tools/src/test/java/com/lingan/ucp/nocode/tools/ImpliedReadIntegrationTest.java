package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.Member;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.lingan.ucp.nocode.api.ApplicationRecords.Delete;
import com.lingan.ucp.nocode.api.ApplicationRecords.Query;
import com.lingan.ucp.nocode.api.ApplicationRecords.Row;
import com.lingan.ucp.nocode.api.ApplicationRecords.Save;
import com.lingan.ucp.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.application.service.sharing.SystemReadAccess;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 关联对象的隐式只读（契约第 15 章）。应用 A 只引用「甲」；甲有关系指向「丙」、数据联动与计算从丙取数、挑取值来自丙的单选字段；丙的一个计算字段从「丁丁」取数，
 * 丙另有一条关系指向「戊戊」。丙、丁丁、戊戊都不在 A 的「已引用对象」里，数据由另一个应用录入。
 *
 * <p>每条用例同时断言「该通的通了」和「不该通的仍然不通」：引用候选、名称回显、联动、计算、挑取值能读丙； 丙不能被当成独立对象读写；丁丁因取数链而可读，戊戊（丙的关系目标）不可读。
 */
class ImpliedReadIntegrationTest {
    private static final Set<String> ALL = Set.of("*");
    private FieldRuleFixture f;
    private ObjectSharingService sharing;
    private ApplicationAuthorizationService authorization;
    private ImpliedObjects implied;
    private SystemReadAccess systemRead;
    private DataCenter.Definition jia, bing, dingding, wuwu;
    private String app;
    private String seed;
    private String reference;
    private final long owner = 10001L;
    private final long member = 24001L;
    private final Map<String, String> bingIds = new LinkedHashMap<>();

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
        f = new FieldRuleFixture();
        sharing = servicesContext.getBean(ObjectSharingService.class);
        authorization = servicesContext.getBean(ApplicationAuthorizationService.class);
        implied = servicesContext.getBean(ImpliedObjects.class);
        systemRead = servicesContext.getBean(SystemReadAccess.class);
        dingding =
                f.object(
                        "dd",
                        List.of(
                                field("code", "编码", "TEXT"),
                                field("amount", "金额", "DECIMAL", 20, 4)),
                        Map.of(),
                        List.of(),
                        List.of());
        wuwu = f.object("ww", List.of(), Map.of(), List.of(), List.of());
        bing =
                f.object(
                        "bing",
                        List.of(
                                field("memo", "备注", "TEXT"),
                                field("code", "编码", "TEXT"),
                                field("amount", "金额", "DECIMAL", 20, 4),
                                field("grade", "等级", "SELECT"),
                                field("dd_sum", "丁丁合计", "FORMULA")),
                        Map.of(
                                "grade",
                                options(
                                        List.of(
                                                new DataCenter.Option("A", "甲级", false),
                                                new DataCenter.Option("B", "乙级", false))),
                                "dd_sum",
                                lookup(dingding, "amount", "code", "code")),
                        List.of(reference("owner", wuwu)),
                        List.of());
        jia =
                f.object(
                        "jia",
                        List.of(
                                field("company", "公司", "TEXT"),
                                field("c_memo", "丙的备注", "TEXT"),
                                field("c_sum", "丙的丁丁合计", "DECIMAL", 20, 4),
                                field("level", "等级", "SELECT"),
                                field("c_amount", "丙金额合计", "FORMULA")),
                        Map.of(
                                "level",
                                placeholder(),
                                "c_amount",
                                lookup(bing, "amount", "code", "company")),
                        List.of(reference("c", bing)),
                        List.of());
        reference = relationField(jia, "c");
        // 规则直接写进已发布版本（夹具的既有做法）：两条数据联动按所选的丙取值，挑取值来自丙的单选字段。
        jia =
                rules(
                        jia,
                        id(jia, "c_memo"),
                        linkage(
                                bing,
                                id(bing, "memo"),
                                "FIRST",
                                List.of(formField(FieldRules.RECORD_KEY, "eq", reference))));
        jia =
                rules(
                        jia,
                        id(jia, "c_sum"),
                        linkage(
                                bing,
                                id(bing, "dd_sum"),
                                "FIRST",
                                List.of(formField(FieldRules.RECORD_KEY, "eq", reference))));
        jia =
                patch(
                        jia,
                        id(jia, "level"),
                        o ->
                                withOptions(o, List.of())
                                        .withSelection(
                                                new SelectionFields.Source(
                                                        "OBJECT_FIELD_OPTIONS",
                                                        null,
                                                        null,
                                                        List.of(),
                                                        false,
                                                        List.of(),
                                                        "NONE",
                                                        null,
                                                        bing.objectId(),
                                                        id(bing, "grade"))));
        // 丙、丁丁的数据由另一个应用录入；被测应用 A 只引用甲。
        seed = f.app(bing, dingding, wuwu);
        f.save(
                seed,
                dingding,
                values(
                        id(dingding, "name"),
                        "丁一",
                        id(dingding, "code"),
                        "K1",
                        id(dingding, "amount"),
                        "10"));
        f.save(
                seed,
                dingding,
                values(
                        id(dingding, "name"),
                        "丁二",
                        id(dingding, "code"),
                        "K1",
                        id(dingding, "amount"),
                        "5"));
        bingRow("丙一", "只在备注里的暗号一", "K1", "3", owner);
        bingRow("丙二", "普通备注", "K1", "4", owner);
        bingRow("丙三", "普通备注", "K2", "100", owner);
        app = f.app(resources(), jia);
        authorize(
                app,
                member,
                new ObjectGrant(
                        jia.objectId(),
                        Set.of("READ", "CREATE", "UPDATE"),
                        "ALL",
                        ALL,
                        ALL,
                        ALL,
                        ALL));
        // 2026-10-04 同步 dev：旧版任务入口已退役（不能新增），入口成员授权随之去掉；本类其余场景只用应用成员。
    }

    @AfterEach
    void cleanup() {
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_application WHERE app_code LIKE ?",
                        Long.class,
                        f.prefix() + "%");
        for (Long id : ids)
            jdbc.update("DELETE FROM public.nocode_task_entry_access WHERE application_id=?", id);
        f.cleanup();
    }

    // ── 夹具 ──

    /** 查找取值：对 target 里 targetMatch 等于本行 localMatch 的记录求 targetField 的和。 */
    private static DataCenter.FieldOptions lookup(
            DataCenter.Definition target,
            String targetField,
            String targetMatch,
            String localMatch) {
        return new DataCenter.FieldOptions(
                null,
                "NORMAL",
                null,
                null,
                null,
                null,
                null,
                "ACTIVE",
                List.of(),
                null,
                "DECIMAL",
                "NONE",
                null,
                false,
                false,
                null,
                new CalculationOptions(
                        "LOOKUP",
                        "LIVE",
                        target.objectId(),
                        null,
                        targetField,
                        "SUM",
                        "AND",
                        List.of(new CalculationOptions.Match(targetMatch, "eq", localMatch, null)),
                        false,
                        List.of(),
                        null));
    }

    private void bingRow(String name, String memo, String code, String amount, long actor) {
        bingIds.put(
                name,
                f.save(
                                seed,
                                bing,
                                values(
                                        id(bing, "name"),
                                        name,
                                        id(bing, "memo"),
                                        memo,
                                        id(bing, "code"),
                                        code,
                                        id(bing, "amount"),
                                        amount,
                                        id(bing, "grade"),
                                        "A"),
                                actor)
                        .id());
    }

    private ApplicationCenter.Resource resource(String id, String kind, Object config) {
        return new ApplicationCenter.Resource(
                id,
                kind,
                "implied_" + id,
                id,
                mapper.convertValue(
                        config,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {}));
    }

    private ApplicationUi.Node node(String fieldId) {
        return new ApplicationUi.Node(
                "n_" + fieldId, "FIELD", fieldId, null, null, null, List.of());
    }

    private List<ApplicationCenter.Resource> resources() {
        return List.of(
                resource(
                        "form",
                        "FORM",
                        new ApplicationUi.Form(
                                jia.objectId(),
                                List.of(
                                        node(id(jia, "name")),
                                        node(id(jia, "company")),
                                        node(reference)),
                                List.of())),
                resource(
                        "view",
                        "VIEW",
                        new ApplicationUi.View(
                                jia.objectId(),
                                List.of(id(jia, "name"), reference),
                                Map.of(),
                                null,
                                false,
                                10,
                                "form")));
    }

    private void authorize(String application, long user, ObjectGrant... grants) {
        authorization.save(
                new ApplicationAuthorization.Save(
                        application,
                        authorization.get(application).revision(),
                        List.of(new Member("USER", Long.toString(user), List.of(grants)))),
                owner);
    }

    private void share(String application, DataCenter.Definition d, ObjectGrant permission) {
        int revision =
                sharing.forApplication(application).stream()
                        .filter(g -> g.objectId().equals(d.objectId()))
                        .findFirst()
                        .map(ObjectSharing.Grant::revision)
                        .orElse(0);
        sharing.save(
                new ObjectSharing.Save(d.objectId(), application, revision, permission, "隐式只读验证"),
                owner);
    }

    private SelectionFields.Result candidates(String search, List<String> selected, long actor) {
        return f.runtime.selection(
                new SelectionFields.Query(
                        app,
                        jia.objectId(),
                        null,
                        reference,
                        search,
                        1,
                        100,
                        selected,
                        null,
                        null,
                        Map.of(),
                        true,
                        null),
                actor);
    }

    private static List<String> labels(SelectionFields.Result result) {
        return result.options().stream().map(SelectionFields.Option::label).sorted().toList();
    }

    private Row jiaRow(String name, String company, String bingName, long actor) {
        return f.runtime
                .save(
                        new Save(
                                app,
                                jia.objectId(),
                                null,
                                null,
                                values(
                                        id(jia, "name"),
                                        name,
                                        id(jia, "company"),
                                        company,
                                        reference,
                                        bingIds.get(bingName)),
                                null),
                        actor)
                .record();
    }

    private Long grantRows(DataCenter.Definition d) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_object_application_grant WHERE"
                        + " application_id=? AND object_id=?",
                Long.class,
                Long.valueOf(app),
                Long.valueOf(d.objectId()));
    }

    private String tableDigest(DataCenter.Definition d) {
        return jdbc.queryForObject(
                "SELECT count(*) || ':' || coalesce(md5(string_agg(md5(t::text), ',' ORDER BY"
                        + " t::text)), '') FROM public.\""
                        + d.tableName()
                        + "\" t",
                String.class);
    }

    private Query page(DataCenter.Definition d) {
        return new Query(app, d.objectId(), 1, 20, null, Map.of(), null, false);
    }

    // ── 用例 ──

    /** 5.6-1：应用只引用甲，甲有关系指向丙，应用照样发布成功；全程没有为丙写任何授权行。 */
    @Test
    void applicationPublishesWithoutReferencingTheRelationTarget() {
        assertThat(f.applications.published(app).versionNo()).isEqualTo(1);
        assertThat(f.applications.published(app).definition().objects())
                .extracting(ApplicationCenter.ObjectReference::objectId)
                .containsExactly(jia.objectId());
        assertThat(grantRows(bing)).as("隐式可读的对象不写授权行").isZero();
        assertThat(grantRows(dingding)).isZero();
        assertThat(implied.implied(app, bing.objectId())).isTrue();
        assertThat(implied.implied(app, jia.objectId())).as("显式引用的不算隐式").isFalse();
    }

    /** 5.6-2：成员在丙上没有任何授权，候选是丙的全部记录、每条只有 ID 与名称；按名称搜得到，按只出现在备注里的词搜不到。 */
    @Test
    void memberWithoutAnyGrantOnTheTargetSeesNamesOnly() {
        SelectionFields.Result all = candidates(null, List.of(), member);
        assertThat(all.total()).isEqualTo(3);
        assertThat(labels(all)).containsExactly("丙一", "丙三", "丙二");
        for (SelectionFields.Option option : all.options()) {
            assertThat(option.value()).isIn(bingIds.values());
            assertThat(option.code()).isEqualTo(option.value());
            assertThat(option.path()).isNull();
            assertThat(option.parentValue()).isNull();
        }
        assertThat(labels(candidates("丙二", List.of(), member))).containsExactly("丙二");
        SelectionFields.Result hidden = candidates("暗号一", List.of(), member);
        assertThat(hidden.total()).as("备注不是名称相关字段，搜索不到它").isZero();
        assertThat(hidden.options()).isEmpty();
        // 已选回显同样只有名称。
        SelectionFields.Result selected = candidates("不存在的词", List.of(bingIds.get("丙三")), member);
        assertThat(selected.selected())
                .extracting(SelectionFields.Option::label)
                .containsExactly("丙三");
    }

    /** 隐式授权对象的形状：操作只有查看；没有明细、关联和任何写；可查看字段只有名称相关字段。 */
    @Test
    void impliedReferenceGrantIsReadOnlyAndNameOnly() {
        var access =
                servicesContext
                        .getBean(
                                com.lingan.ucp.nocode.runtime.service.access
                                        .ApplicationRuntimePolicy.class)
                        .referenceAccess(
                                app, jia, jia.relations().getFirst(), bing, member, Set.of(), null);
        assertThat(access.grants()).hasSize(1);
        ObjectGrant grant = access.grants().getFirst();
        assertThat(grant.actions()).containsExactly("READ");
        assertThat(grant.scope()).isEqualTo("ALL");
        assertThat(grant.readFields()).containsExactly(id(bing, "name"));
        assertThat(grant.writeFields()).isEmpty();
        assertThat(grant.readDetails()).isEmpty();
        assertThat(grant.writeDetails()).isEmpty();
        assertThat(grant.readRelations()).isEmpty();
        assertThat(grant.writeRelations()).isEmpty();
        assertThat(grant.actionScopes()).isEmpty();
    }

    /** 5.6-3：成员保存一条引用了丙的甲；列表与详情里显示所选记录的名称。 */
    @Test
    void memberSavesAReferenceAndSeesItsName() {
        Row saved = jiaRow("甲一", "K1", "丙二", member);
        assertThat(saved.values()).containsEntry(reference, bingIds.get("丙二"));
        Row detail = f.runtime.get(app, jia.objectId(), saved.id(), member).record();
        assertThat(detail.displayValues()).containsEntry(reference, "丙二");
        Row listed = f.runtime.page(page(jia), member).getList().getFirst();
        assertThat(listed.displayValues()).containsEntry(reference, "丙二");
        // 不存在的丙记录仍然存不进去。
        assertThatThrownBy(
                        () ->
                                f.runtime.save(
                                        new Save(
                                                app,
                                                jia.objectId(),
                                                null,
                                                null,
                                                values(
                                                        id(jia, "name"),
                                                        "引用不存在",
                                                        reference,
                                                        "999999999"),
                                                null),
                                        member))
                .isInstanceOf(com.lingan.ucp.framework.common.exception.ServiceException.class);
    }

    /** 5.6-4（硬边界）：丙不能被当成独立对象使用——列表、详情、保存、删除、导出、模型全部被拒，丙的数据逐行不变。 */
    @Test
    void impliedObjectCannotBeUsedAsAnObjectOfItsOwn() {
        String before = tableDigest(bing);
        String id = bingIds.get("丙一");
        for (long actor : List.of(member, owner)) {
            assertThatThrownBy(() -> f.runtime.page(page(bing), actor))
                    .hasMessage("该对象不属于应用的已发布版本");
            assertThatThrownBy(() -> f.runtime.get(app, bing.objectId(), id, actor))
                    .hasMessage("该对象不属于应用的已发布版本");
            assertThatThrownBy(() -> f.runtime.model(app, bing.objectId(), actor))
                    .hasMessage("该对象不属于应用的已发布版本");
            assertThatThrownBy(() -> f.runtime.export(page(bing), actor))
                    .hasMessage("该对象不属于应用的已发布版本");
            assertThatThrownBy(
                            () ->
                                    f.runtime.save(
                                            new Save(
                                                    app,
                                                    bing.objectId(),
                                                    null,
                                                    null,
                                                    values(id(bing, "name"), "越界新建"),
                                                    null),
                                            actor))
                    .hasMessage("该对象不属于应用的已发布版本");
            assertThatThrownBy(
                            () ->
                                    f.runtime.save(
                                            new Save(
                                                    app,
                                                    bing.objectId(),
                                                    id,
                                                    null,
                                                    values(id(bing, "memo"), "越界修改"),
                                                    null),
                                            actor))
                    .hasMessage("该对象不属于应用的已发布版本");
            assertThatThrownBy(
                            () ->
                                    f.runtime.delete(
                                            new Delete(app, bing.objectId(), id, null), actor))
                    .hasMessage("该对象不属于应用的已发布版本");
        }
        assertThat(tableDigest(bing)).as("丙的数据前后逐行不变").isEqualTo(before);
    }

    /** 5.6-5：计算从丙取数、挑取值来自丙的单选字段、数据联动读丙——全程没有引用过丙、没有为丙配过授权。 */
    @Test
    void calculationPickAndLinkageReadTheImpliedObject() {
        Row saved = jiaRow("甲一", "K1", "丙一", member);
        Row reread = f.runtime.get(app, jia.objectId(), saved.id(), member).record();
        assertThat(reread.values())
                .as("计算：丙里编码为 K1 的金额合计 3 + 4")
                .containsEntry(id(jia, "c_amount"), "7.0000000000");
        SelectionFields.Result pick =
                f.runtime.selection(
                        new SelectionFields.Query(
                                app,
                                jia.objectId(),
                                null,
                                id(jia, "level"),
                                null,
                                1,
                                100,
                                List.of(),
                                null,
                                null,
                                Map.of(),
                                true,
                                null),
                        member);
        assertThat(pick.options())
                .extracting(SelectionFields.Option::label)
                .containsExactly("甲级", "乙级");
        FieldRules.Evaluation evaluation =
                f.evaluate(
                        app,
                        jia,
                        values(reference, bingIds.get("丙一")),
                        List.of(),
                        List.of(),
                        null,
                        member);
        FieldRules.Result memo = result(evaluation, id(jia, "c_memo"));
        assertThat(memo.state()).isEqualTo("APPLIED");
        assertThat(memo.value()).isEqualTo("只在备注里的暗号一");
        assertThat(grantRows(bing)).isZero();
    }

    /** 5.6-6：取数链——甲经联动读丙的计算字段，而它从丁丁取数；丁丁因此隐式可读，丙的关系目标戊戊不可读。 */
    @Test
    void dataSourceChainIsImpliedButRelationTargetsOfImpliedObjectsAreNot() {
        FieldRules.Result sum =
                result(
                        f.evaluate(
                                app,
                                jia,
                                values(reference, bingIds.get("丙一")),
                                List.of(),
                                List.of(),
                                null,
                                member),
                        id(jia, "c_sum"));
        assertThat(sum.state()).as(sum.message()).isEqualTo("APPLIED");
        assertThat(new java.math.BigDecimal(sum.value().toString()))
                .as("丁丁里编码为 K1 的金额合计 10 + 5")
                .isEqualByComparingTo("15");
        assertThat(implied.implied(app, dingding.objectId())).isTrue();
        assertThat(implied.implied(app, wuwu.objectId())).as("丙的关系目标不传递").isFalse();
        assertThat(systemRead.check(app, wuwu, Set.of()).code()).isEqualTo("NOT_GRANTED");
        assertThatThrownBy(() -> f.runtime.page(page(wuwu), owner)).hasMessage("该对象不属于应用的已发布版本");
        assertThatThrownBy(() -> f.runtime.page(page(dingding), owner))
                .as("丁丁只是取数来源，同样不能当独立对象用")
                .hasMessage("该对象不属于应用的已发布版本");
        assertThat(grantRows(dingding)).isZero();
    }

    /** 5.6-7：有效上限的四种情况。 */
    @Test
    void effectiveCeilingFollowsTheExplicitRowWhenThereIsOne() {
        Row saved = jiaRow("甲一", "K1", "丙一", member);
        // ① 无行 + 隐式 ⇒ 通（其余用例已覆盖），这里只确认系统取数前提成立。
        assertThat(systemRead.check(app, bing, Set.of(id(bing, "amount")))).isNull();

        // ② 有行未撤销，收窄成「只看本人创建的」：候选只有本人创建的；计算按 SCOPE 文案被拦。
        authorize(
                seed,
                member,
                new ObjectGrant(
                        bing.objectId(), Set.of("READ", "CREATE"), "ALL", ALL, ALL, ALL, ALL));
        bingRow("成员建的丙", "普通备注", "K9", "1", member);
        share(
                app,
                bing,
                new ObjectGrant(
                        bing.objectId(), Set.of("READ"), "OWN", ALL, Set.of(), ALL, Set.of()));
        assertThat(labels(candidates(null, List.of(), member))).containsExactly("成员建的丙");
        String applicationName = f.applications.get(app).application().name();
        assertThatThrownBy(() -> f.runtime.get(app, jia.objectId(), saved.id(), member))
                .hasMessage(
                        "「对象jia」的「丙金额合计」要从「对象bing」取数，但现在取不了：「对象bing」授给应用「"
                                + applicationName
                                + "」的记录范围是“当前操作者创建的记录”，系统计算必须能读全部记录。\n去哪改：「对象bing」没有被本应用引用，"
                                + "是因关联而可读取的对象，不在“已引用对象”里。请到 数据中心 → 数据对象 →「对象bing」→ 应用共享授权 → 应用「"
                                + applicationName
                                + "」→ 记录范围选“全部记录”。\n"
                                + "没有数据权限管理权限时，请联系数据管理员处理。");

        // ③ 有行已撤销：候选为空、名称显示「已失效或无权限的引用」、系统取数 NOT_GRANTED。
        share(app, bing, null);
        SelectionFields.Result revoked = candidates(null, List.of(bingIds.get("丙一")), member);
        assertThat(revoked.total()).isZero();
        assertThat(revoked.options()).isEmpty();
        assertThat(revoked.selected())
                .as("已选的不回显名称，显示成失效引用")
                .singleElement()
                .satisfies(
                        option -> {
                            assertThat(option.label()).isEqualTo("已失效或无权限的引用");
                            assertThat(option.unavailable()).isTrue();
                            assertThat(option.disabled()).isTrue();
                        });
        assertThat(systemRead.check(app, bing, Set.of()).code()).isEqualTo("NOT_GRANTED");
        // 丙没有被应用引用：「去哪改」不能指到“已引用对象”里的一行（那张表里没有它），要指到数据中心里丙的应用共享授权。
        assertThatThrownBy(() -> f.runtime.get(app, jia.objectId(), saved.id(), member))
                .hasMessage(
                        "「对象jia」的「丙金额合计」要从「对象bing」取数，但现在取不了：「对象bing」还没有授权给应用「"
                                + applicationName
                                + "」（或授权已撤销）。\n去哪改：「对象bing」没有被本应用引用，是因关联而可读取的对象，不在“已引用对象”里。"
                                + "请到 数据中心 → 数据对象 →「对象bing」→ 应用共享授权 → 应用「"
                                + applicationName
                                + "」→ 新增应用授权并保存。\n没有数据权限管理权限时，请联系数据管理员处理。");
        FieldRules.Result memo =
                result(
                        f.evaluate(
                                app,
                                jia,
                                values(reference, bingIds.get("丙一")),
                                List.of(),
                                List.of(),
                                null,
                                member),
                        id(jia, "c_memo"));
        assertThat(memo.state()).isEqualTo("SOURCE_NOT_READABLE");
        assertThat(memo.message())
                .isEqualTo("「对象bing」还没有授权给应用「" + applicationName + "」（或授权已撤销），不填值");
        assertThat(implied.readable(readableQuery()))
                .filteredOn(item -> item.object().objectId().equals(bing.objectId()))
                .extracting(ApplicationReadableObjects.Item::closed)
                .containsExactly(true);

        // ④ 无行 + 非隐式 ⇒ 拒。
        assertThat(systemRead.check(app, wuwu, Set.of()).code()).isEqualTo("NOT_GRANTED");
    }

    /** 已撤销时列表里的名称回显：显示「已失效或无权限的引用」，而不是丙的名称。 */
    @Test
    void revokedGrantHidesTheReferenceLabel() {
        Row saved = jiaRow("甲一", "K2", "丙三", member);
        share(app, bing, null);
        // 计算字段此时取不了数会整行报错，所以用只含引用字段的入口看回显。
        assertThatThrownBy(() -> f.runtime.get(app, jia.objectId(), saved.id(), member))
                .hasMessageContaining("还没有授权给应用");
        share(
                app,
                bing,
                new ObjectGrant(
                        bing.objectId(),
                        Set.of("READ"),
                        "ALL",
                        Set.of(id(bing, "amount"), id(bing, "code")),
                        Set.of(),
                        Set.of(),
                        Set.of()));
        Row detail = f.runtime.get(app, jia.objectId(), saved.id(), member).record();
        assertThat(detail.displayValues())
                .as("上限里没有名称字段：显示不出名称，也不会漏出别的字段")
                .containsEntry(reference, "未提供可见标题");
    }

    /** 5.6-8：并集——丙被显式引用且成员在它上面有全字段查看权时，候选可以按非名称字段搜到；显式引用但成员无授权时仍能看到名称并选择（行为变化）。 */
    @Test
    void explicitMemberGrantOnTheTargetIsUnionedIn() {
        String both = f.app(jia, bing);
        // 成员只在甲上有授权：能看到丙的名称并选择，但按备注搜不到。
        authorize(
                both,
                member,
                new ObjectGrant(
                        jia.objectId(), Set.of("READ", "CREATE"), "ALL", ALL, ALL, ALL, ALL));
        SelectionFields.Query query =
                new SelectionFields.Query(
                        both,
                        jia.objectId(),
                        null,
                        reference,
                        "暗号一",
                        1,
                        100,
                        List.of(),
                        null,
                        null,
                        Map.of(),
                        true,
                        null);
        assertThat(f.runtime.selection(query, member).total()).isZero();
        SelectionFields.Query everything =
                new SelectionFields.Query(
                        both,
                        jia.objectId(),
                        null,
                        reference,
                        null,
                        1,
                        100,
                        List.of(),
                        null,
                        null,
                        Map.of(),
                        true,
                        null);
        assertThat(f.runtime.selection(everything, member).total())
                .as("行为变化：成员在目标对象上没有授权也能看到名称并选择")
                .isEqualTo(3);
        // 成员在丙上有全字段查看权：可以按备注搜到。
        authorize(
                both,
                member,
                new ObjectGrant(
                        jia.objectId(), Set.of("READ", "CREATE"), "ALL", ALL, ALL, ALL, ALL),
                new ObjectGrant(
                        bing.objectId(), Set.of("READ"), "ALL", ALL, Set.of(), ALL, Set.of()));
        SelectionFields.Result found = f.runtime.selection(query, member);
        assertThat(labels(found)).containsExactly("丙一");
    }

    private ApplicationReadableObjects.Query readableQuery() {
        return new ApplicationReadableObjects.Query(app, f.applications.get(app).draft().objects());
    }

    /** 5.6-13：工作台接口返回丙、丁丁及各自的原因；戊戊不在；引用的校验和不对按现有文案拒绝。 */
    @Test
    void readableObjectsEndpointListsImpliedObjectsWithReasons() {
        List<ApplicationReadableObjects.Item> items = implied.readable(readableQuery());
        ApiSamples.print("POST /nocode/application/readable-objects", readableQuery(), items);
        assertThat(items)
                .extracting(item -> item.object().objectId())
                .containsExactly(bing.objectId(), dingding.objectId());
        ApplicationReadableObjects.Item first = items.getFirst();
        assertThat(first.closed()).isFalse();
        assertThat(first.object().versionNo()).isEqualTo(1);
        assertThat(first.object().definition().objectName()).isEqualTo("对象bing");
        assertThat(first.via())
                .extracting(ApplicationReadableObjects.Via::kind)
                .contains("RELATION", "LINKAGE", "PICK", "CALCULATION");
        assertThat(first.via())
                .allSatisfy(
                        via -> {
                            assertThat(via.fromObjectId()).isEqualTo(jia.objectId());
                            assertThat(via.fromObjectName()).isEqualTo("对象jia");
                        });
        assertThat(first.via())
                .filteredOn(via -> via.kind().equals("RELATION"))
                .extracting(ApplicationReadableObjects.Via::name)
                .containsExactly("引用c");
        assertThat(items.get(1).via())
                .containsExactly(
                        new ApplicationReadableObjects.Via(
                                bing.objectId(), "对象bing", "CALCULATION", "丁丁合计"));
        ApplicationCenter.ObjectReference pinned =
                f.applications.get(app).draft().objects().getFirst();
        assertThatThrownBy(
                        () ->
                                implied.readable(
                                        new ApplicationReadableObjects.Query(
                                                app,
                                                List.of(
                                                        new ApplicationCenter.ObjectReference(
                                                                pinned.objectId(),
                                                                pinned.versionNo(),
                                                                "0".repeat(64))))))
                .hasMessage("引用对象版本校验和不匹配，请重新选择版本");
    }

    /** 5.6-12：设计预览（规则预览、选择预览）在草稿没有引用丙时可用；预览的主对象仍只认显式引用。 */
    @Test
    void designPreviewsResolveImpliedObjectsFromTheDraft() {
        List<ApplicationCenter.ObjectReference> objects = f.applications.get(app).draft().objects();
        FieldRules.Evaluation evaluation =
                f.runtime.previewRules(
                        new FieldRules.EvaluatePreview(
                                new FieldRules.EvaluateQuery(
                                        app,
                                        jia.objectId(),
                                        null,
                                        null,
                                        true,
                                        values(reference, bingIds.get("丙一")),
                                        List.of(),
                                        List.of(),
                                        null),
                                objects,
                                null),
                        owner);
        assertThat(result(evaluation, id(jia, "c_memo")).value()).isEqualTo("只在备注里的暗号一");
        SelectionFields.Result preview =
                f.runtime.previewSelection(
                        new SelectionFields.PreviewQuery(
                                new SelectionFields.Query(
                                        app,
                                        jia.objectId(),
                                        null,
                                        reference,
                                        null,
                                        1,
                                        100,
                                        List.of(),
                                        null,
                                        null,
                                        Map.of(),
                                        true,
                                        null),
                                objects,
                                null,
                                false),
                        owner);
        assertThat(labels(preview)).containsExactly("丙一", "丙三", "丙二");
        assertThatThrownBy(
                        () ->
                                f.runtime.previewRules(
                                        new FieldRules.EvaluatePreview(
                                                new FieldRules.EvaluateQuery(
                                                        app,
                                                        bing.objectId(),
                                                        null,
                                                        null,
                                                        true,
                                                        Map.of(),
                                                        List.of(),
                                                        List.of(),
                                                        null),
                                                objects,
                                                null),
                                        owner))
                .hasMessage("求值对象不属于当前草稿引用");
    }

    /** 5.6-11：资源归属仍只认显式引用——为丙建视图、表单仍按原文案被拒（任务入口已随 dev 退役，新增一律被拒，不再单测）；目标对象停用时给出新文案。 */
    @Test
    void resourcesStillRequireAnExplicitReference() {
        ApplicationCenter.Detail head = f.applications.get(app);
        java.util.function.Function<ApplicationCenter.Resource, ApplicationCenter.Save> with =
                extra -> {
                    List<ApplicationCenter.Resource> all =
                            new ArrayList<>(head.draft().resources());
                    all.add(extra);
                    return new ApplicationCenter.Save(
                            app,
                            head.application().revision(),
                            head.application().code(),
                            head.application().name(),
                            null,
                            null,
                            new ApplicationCenter.Definition(head.draft().objects(), all));
                };
        assertThatThrownBy(
                        () ->
                                f.applications.save(
                                        with.apply(
                                                resource(
                                                        "bing_view",
                                                        "VIEW",
                                                        new ApplicationUi.View(
                                                                bing.objectId(),
                                                                List.of(id(bing, "name")),
                                                                Map.of(),
                                                                null,
                                                                false,
                                                                10,
                                                                null))),
                                        owner))
                .hasMessage("资源绑定对象不属于应用");
        assertThatThrownBy(
                        () ->
                                f.applications.save(
                                        with.apply(
                                                resource(
                                                        "bing_form",
                                                        "FORM",
                                                        new ApplicationUi.Form(
                                                                bing.objectId(),
                                                                List.of(node(id(bing, "name"))),
                                                                List.of()))),
                                        owner))
                .hasMessage("资源绑定对象不属于应用");
        // 丙停用后：应用发布校验点名是哪条关系指向了取不到的对象。
        jdbc.update(
                "UPDATE public.nocode_object SET status='DISABLED' WHERE id=?",
                Long.valueOf(bing.objectId()));
        try {
            assertThatThrownBy(
                            () ->
                                    f.applications.publish(
                                            new ApplicationCenter.Revision(
                                                    app, head.application().revision(), "目标已停用"),
                                            owner))
                    .hasMessage(
                            "对象“对象jia”的计算“丙金额合计”指向的对象“对象bing”未发布或已停用，应用无法读取它。请先在数据中心发布或启用“对象bing”。");
        } finally {
            jdbc.update(
                    "UPDATE public.nocode_object SET status='ACTIVE' WHERE id=?",
                    Long.valueOf(bing.objectId()));
        }
    }

    /** 5.6-9：把甲移出应用后，丙不再隐式可读（隐式集合按应用发布版本现算，没有残留）。 */
    @Test
    void impliedSetShrinksWhenTheReferencingObjectLeaves() {
        assertThat(implied.implied(app, bing.objectId())).isTrue();
        String other = f.app(dingding);
        assertThat(implied.implied(other, bing.objectId())).isFalse();
        // 应用 A 换成只引用丁丁并发布：丙随之退出。
        ApplicationCenter.Detail head = f.applications.get(app);
        ApplicationCenter.ObjectReference reference =
                f.applications.get(other).draft().objects().getFirst();
        ApplicationCenter.Detail saved =
                f.applications.save(
                        new ApplicationCenter.Save(
                                app,
                                head.application().revision(),
                                head.application().code(),
                                head.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(List.of(reference), List.of())),
                        owner);
        f.applications.publish(
                new ApplicationCenter.Revision(app, saved.application().revision(), "移出甲"), owner);
        assertThat(f.applications.published(app).versionNo()).isEqualTo(2);
        assertThat(implied.implied(app, bing.objectId())).as("发布版本变了，隐式集合随之更新").isFalse();
        assertThat(systemRead.check(app, bing, Set.of()).code()).isEqualTo("NOT_GRANTED");
    }
}
