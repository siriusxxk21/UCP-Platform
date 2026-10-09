package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.FieldRuleFixture.*;
import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 引用筛选与数据联动条件的「不等于 / 为空 / 不为空」（业务方 2026-10-01：录凭证时只想选「凭证状态不是已录入、或还没有状态」的资金流水）。
 * 当前开发库真实读写；候选查询、保存时引用复核、联动求值三条路径走同一套条件，口径必须一致：
 *
 * <ul>
 *   <li>「不等于」对空值安全：来源字段为空（NULL 或空串）的行算不等于，会被选中；
 *   <li>「为空 / 不为空」不带比较值；文本与选项把空串也算空，多选把空列表也算空。
 * </ul>
 */
class RuleConditionOperatorsIntegrationTest {
    private static final String DONE = "已录入流水";
    private static final String TODO = "未录入流水";
    private static final String NONE = "无状态流水";
    private static final String BLANK = "空串流水";

    private FieldRuleFixture f;
    private DataCenter.Definition company;
    private DataCenter.Definition flow;
    private DataCenter.Definition voucher;
    private String app;
    private String reference;
    private final Map<String, String> ids = new LinkedHashMap<>();

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
        company = f.object("company", List.of(), Map.of(), List.of(), List.of());
        flow =
                f.object(
                        "flow",
                        List.of(
                                field("status", "凭证状态", "SELECT"),
                                field("memo", "备注", "TEXT"),
                                field("qty", "数量", "INTEGER"),
                                field("opened", "发生日期", "DATE"),
                                field("tags", "标签", "MULTI_SELECT")),
                        Map.of(
                                "status",
                                options(
                                        List.of(
                                                new DataCenter.Option("DONE", "已录入", false),
                                                new DataCenter.Option("TODO", "未录入", false))),
                                "tags",
                                options(
                                        List.of(
                                                new DataCenter.Option("A", "甲类", false),
                                                new DataCenter.Option("B", "乙类", false)))),
                        List.of(reference("company", company)),
                        List.of());
        voucher =
                f.object(
                        "voucher",
                        List.of(field("picked", "命中流水", "TEXT")),
                        Map.of(),
                        List.of(reference("flow", flow)),
                        List.of());
        reference = relationField(voucher, "flow");
        app = f.app(company, flow, voucher);
        String first = f.save(app, company, values(id(company, "name"), "甲公司")).id();
        String second = f.save(app, company, values(id(company, "name"), "乙公司")).id();
        ids.put("甲公司", first);
        ids.put("乙公司", second);
        row(DONE, "DONE", "有备注", 10, "2026-01-01", List.of("A"), first);
        row(TODO, "TODO", "待办", 5, "2026-02-01", List.of("B"), second);
        // 两种「空」各一行：一行各列都是 NULL；另一行把选项、文本写成空串、多选写成空列表（导入或旧数据会留下这种值）。
        row(NONE, null, null, null, null, null, null);
        row(BLANK, null, null, null, null, null, null);
        int blanked =
                jdbc.update(
                        "UPDATE public.\""
                                + flow.tableName()
                                + "\" SET \""
                                + column("status")
                                + "\"='', \""
                                + column("memo")
                                + "\"='', \""
                                + column("tags")
                                + "\"='[]'::jsonb WHERE id=?",
                        Long.valueOf(ids.get(BLANK)));
        assertThat(blanked).isEqualTo(1);
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    private String column(String code) {
        return flow.fieldOptions().get(id(flow, code)).columnName();
    }

    private void row(
            String name,
            String status,
            String memo,
            Integer qty,
            String opened,
            List<String> tags,
            String companyId) {
        var values = values(id(flow, "name"), name);
        if (status != null) values.put(id(flow, "status"), status);
        if (memo != null) values.put(id(flow, "memo"), memo);
        if (qty != null) values.put(id(flow, "qty"), qty);
        if (opened != null) values.put(id(flow, "opened"), opened);
        if (tags != null) values.put(id(flow, "tags"), tags);
        if (companyId != null) values.put(relationField(flow, "company"), companyId);
        ids.put(name, f.save(app, flow, values).id());
    }

    private static FieldRules.Condition empty(String sourceField, String operator) {
        return new FieldRules.Condition(sourceField, operator, "CONSTANT", null, null);
    }

    /** 把引用筛选换成给定条件后查候选，返回候选标签（按名称排序）。 */
    private List<String> candidates(FieldRules.Condition... conditions) {
        voucher =
                FieldRuleFixture.rules(
                        voucher, reference, FieldRuleFixture.filter(null, conditions));
        var result = f.selection(app, voucher, reference, Map.of(), List.of());
        assertThat(result.ruleState()).as(result.ruleMessage()).isNull();
        assertThat(result.total()).isEqualTo(result.options().size());
        return result.options().stream().map(SelectionFields.Option::label).sorted().toList();
    }

    private static List<String> sorted(String... names) {
        return Arrays.stream(names).sorted().toList();
    }

    /** 夹具自检：四行都在，且「空串流水」的三列确实是空串 / 空列表而不是 NULL（否则下文分不出两种空）。 */
    @Test
    void fixtureHasBothKindsOfEmpty() {
        assertThat(candidates()).isEqualTo(sorted(DONE, TODO, NONE, BLANK));
        var stored =
                jdbc.queryForMap(
                        "SELECT \""
                                + column("status")
                                + "\" AS status, \""
                                + column("memo")
                                + "\" AS memo, \""
                                + column("tags")
                                + "\"::text AS tags FROM public.\""
                                + flow.tableName()
                                + "\" WHERE id=?",
                        Long.valueOf(ids.get(BLANK)));
        assertThat(stored).containsEntry("status", "").containsEntry("memo", "");
        assertThat(stored.get("tags")).isEqualTo("[]");
        var none =
                jdbc.queryForMap(
                        "SELECT \""
                                + column("status")
                                + "\" AS status FROM public.\""
                                + flow.tableName()
                                + "\" WHERE id=?",
                        Long.valueOf(ids.get(NONE)));
        assertThat(none.get("status")).isNull();
    }

    /** 业务方场景：凭证状态「不等于 已录入」选中其它选项与没有状态的流水，不选中已录入的。 */
    @Test
    void selectNotEqualKeepsRowsWithoutValue() {
        assertThat(candidates(constant(id(flow, "status"), "neq", "DONE")))
                .isEqualTo(sorted(TODO, NONE, BLANK));
        assertThat(candidates(constant(id(flow, "status"), "eq", "DONE"))).containsExactly(DONE);
        // 等于与不等于互补：两者合起来正好是全量，没有哪一行两头都选不中。
        assertThat(candidates(constant(id(flow, "status"), "neq", "TODO")))
                .isEqualTo(sorted(DONE, NONE, BLANK));
    }

    /** 选项与文本的「为空」把 NULL 与空串都算空；「不为空」正好相反；文本「不等于」同样留下空值行。 */
    @Test
    void emptyTreatsNullAndBlankStringAlike() {
        assertThat(candidates(empty(id(flow, "status"), "isNull"))).isEqualTo(sorted(NONE, BLANK));
        assertThat(candidates(empty(id(flow, "status"), "notNull"))).isEqualTo(sorted(DONE, TODO));
        assertThat(candidates(empty(id(flow, "memo"), "isNull"))).isEqualTo(sorted(NONE, BLANK));
        assertThat(candidates(empty(id(flow, "memo"), "notNull"))).isEqualTo(sorted(DONE, TODO));
        assertThat(candidates(constant(id(flow, "memo"), "neq", "有备注")))
                .isEqualTo(sorted(TODO, NONE, BLANK));
    }

    /** 数值「不等于」含空值行（行为变化：原为裸 &lt;&gt;，空值行选不中）；数值、日期、多选、关联字段的为空 / 不为空。 */
    @Test
    void numberDateMultiSelectAndReference() {
        assertThat(candidates(constant(id(flow, "qty"), "neq", 10)))
                .isEqualTo(sorted(TODO, NONE, BLANK));
        assertThat(candidates(empty(id(flow, "qty"), "isNull"))).isEqualTo(sorted(NONE, BLANK));
        assertThat(candidates(empty(id(flow, "qty"), "notNull"))).isEqualTo(sorted(DONE, TODO));
        assertThat(candidates(empty(id(flow, "opened"), "isNull"))).isEqualTo(sorted(NONE, BLANK));
        assertThat(candidates(empty(id(flow, "opened"), "notNull"))).isEqualTo(sorted(DONE, TODO));
        // 多选：NULL 与空列表都算空。
        assertThat(candidates(empty(id(flow, "tags"), "isNull"))).isEqualTo(sorted(NONE, BLANK));
        assertThat(candidates(empty(id(flow, "tags"), "notNull"))).isEqualTo(sorted(DONE, TODO));
        String owner = relationField(flow, "company");
        assertThat(candidates(constant(owner, "neq", ids.get("甲公司"))))
                .isEqualTo(sorted(TODO, NONE, BLANK));
        assertThat(candidates(empty(owner, "isNull"))).isEqualTo(sorted(NONE, BLANK));
        assertThat(candidates(empty(owner, "notNull"))).isEqualTo(sorted(DONE, TODO));
    }

    /** 条件之间仍是「且」：不等于与不为空叠加只剩有值且不等的行。 */
    @Test
    void conditionsStayAnd() {
        assertThat(
                        candidates(
                                constant(id(flow, "status"), "neq", "DONE"),
                                empty(id(flow, "status"), "notNull")))
                .containsExactly(TODO);
    }

    /** 保存时的引用复核与候选同一口径：候选里有的能存，候选里没有的被拒；已选值超出范围照常回显并标不可选。 */
    @Test
    void saveValidationFollowsCandidates() {
        voucher =
                FieldRuleFixture.rules(
                        voucher,
                        reference,
                        FieldRuleFixture.filter(null, constant(id(flow, "status"), "neq", "DONE")));
        assertThatThrownBy(() -> save("选已录入", DONE)).hasMessageContaining("所选记录不符合对象引用筛选");
        for (String name : List.of(TODO, NONE, BLANK))
            assertThat(save("选" + name, name).id()).as(name).isNotBlank();
        var echoed =
                f.selection(
                        app, voucher, reference, Map.of(), List.of(ids.get(DONE), ids.get(NONE)));
        assertThat(echoed.selected()).hasSize(2);
        assertThat(echoed.selected().get(0).label()).isEqualTo(DONE);
        assertThat(echoed.selected().get(0).disabled()).as("超出范围：回显但不可重新选入").isTrue();
        assertThat(echoed.selected().get(1).disabled()).isFalse();

        voucher =
                FieldRuleFixture.rules(
                        voucher,
                        reference,
                        FieldRuleFixture.filter(null, empty(id(flow, "status"), "isNull")));
        assertThatThrownBy(() -> save("为空选未录入", TODO)).hasMessageContaining("所选记录不符合对象引用筛选");
        assertThat(save("为空选空串", BLANK).id()).isNotBlank();
        voucher =
                FieldRuleFixture.rules(
                        voucher,
                        reference,
                        FieldRuleFixture.filter(null, empty(id(flow, "status"), "notNull")));
        assertThatThrownBy(() -> save("不为空选空串", BLANK)).hasMessageContaining("所选记录不符合对象引用筛选");
        assertThat(save("不为空选未录入", TODO).id()).isNotBlank();
    }

    private ApplicationRecords.Row save(String name, String flowName) {
        return f.save(
                app, voucher, values(id(voucher, "name"), name, reference, ids.get(flowName)));
    }

    /** 数据联动的条件（来源侧字段）用同一套算子：命中行与候选一致，按创建顺序拼接。 */
    @Test
    void linkageConditionsUseSameSemantics() {
        assertThat(linked(constant(id(flow, "status"), "neq", "DONE")))
                .isEqualTo(String.join(",", TODO, NONE, BLANK));
        assertThat(linked(empty(id(flow, "status"), "isNull")))
                .isEqualTo(String.join(",", NONE, BLANK));
        assertThat(linked(empty(id(flow, "memo"), "notNull"), constant(id(flow, "qty"), "neq", 10)))
                .isEqualTo(TODO);
        assertThat(linked(empty(id(flow, "tags"), "isNull")))
                .isEqualTo(String.join(",", NONE, BLANK));
    }

    private Object linked(FieldRules.Condition... conditions) {
        String target = id(voucher, "picked");
        voucher =
                FieldRuleFixture.rules(
                        voucher,
                        target,
                        linkage(flow, id(flow, "name"), "CONCAT", List.of(conditions)));
        var r =
                result(
                        f.evaluate(app, voucher, Map.of(), List.of(), List.of(), null, 10001),
                        target);
        assertThat(r.state()).as(r.message()).isEqualTo("APPLIED");
        assertThat(r.matchedRows()).isEqualTo(r.value().toString().split(",").length);
        return r.value();
    }

    /** 「为空」夹带比较值的脏配置（绕过发布校验写进快照）运行期不放大命中范围：候选为空并给出状态。 */
    @Test
    void emptinessWithValueFailsClosed() {
        voucher =
                FieldRuleFixture.rules(
                        voucher,
                        reference,
                        FieldRuleFixture.filter(
                                null, constant(id(flow, "status"), "isNull", "DONE")));
        var result = f.selection(app, voucher, reference, Map.of(), List.of());
        assertThat(result.ruleState()).isEqualTo("CONDITION_UNSUPPORTED");
        assertThat(result.options()).isEmpty();
        assertThat(result.total()).isZero();
    }

    /**
     * 正式保存链路（对象设计保存 → 发布）：选项字段的固定值填了选项名称「已录入」被拒——库里存的是编码 DONE，这样的条件永远筛不到。 业务方 2026-10-01
     * 实际踩到：填了名称，候选恒为空，发布也没拦。
     */
    @Test
    void designSaveRejectsOptionNameAsConstant() {
        assertThatThrownBy(() -> republishFilter(constant(id(flow, "status"), "eq", "已录入")))
                .hasMessageContaining("引用筛选：条件字段「凭证状态」的固定值「已录入」不是该字段的有效选项，请从选项中选择");
    }

    /** 同一链路填选项编码通过，规则进了发布快照。 */
    @Test
    void designSaveAcceptsOptionCode() {
        var saved = republishFilter(constant(id(flow, "status"), "neq", "DONE"));
        var filter = saved.fieldOptions().get(reference).rules().reference().filter();
        assertThat(filter)
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.operator()).isEqualTo("neq");
                            assertThat(c.value()).isEqualTo("DONE");
                        });
        var empty = republishFilter(empty(id(flow, "status"), "isNull"));
        assertThat(empty.fieldOptions().get(reference).rules().reference().filter())
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.operator()).isEqualTo("isNull");
                            assertThat(c.value()).isNull();
                            assertThat(c.formFieldId()).isNull();
                        });
    }

    /**
     * 应用发布按固定版本复核：固定的对象版本里留着「固定值是选项名称」的旧配置时（今天的设计保存已拦，这里绕过保存直接写进已发布快照），
     * 应用发布被拒，报错点名字段与值；换成选项编码后同样的应用照常发布。
     */
    @Test
    void applicationPublishRejectsPinnedOptionNameConstant() {
        voucher =
                FieldRuleFixture.rules(
                        voucher,
                        reference,
                        FieldRuleFixture.filter(null, constant(id(flow, "status"), "eq", "已录入")));
        assertThatThrownBy(() -> f.app(company, flow, voucher))
                .hasMessageContaining("引用筛选：条件字段「凭证状态」的固定值「已录入」不是该字段的有效选项，请从选项中选择");
        voucher =
                FieldRuleFixture.rules(
                        voucher,
                        reference,
                        FieldRuleFixture.filter(null, constant(id(flow, "status"), "eq", "DONE")));
        assertThatCode(() -> f.app(company, flow, voucher)).doesNotThrowAnyException();
    }

    private DataCenter.Definition republishFilter(FieldRules.Condition condition) {
        var current =
                voucher.fieldOptions().getOrDefault(reference, DataCenter.FieldOptions.defaults());
        voucher =
                f.republish(
                        voucher,
                        Map.of(
                                reference,
                                current.withRules(FieldRuleFixture.filter(null, condition))));
        return voucher;
    }
}
