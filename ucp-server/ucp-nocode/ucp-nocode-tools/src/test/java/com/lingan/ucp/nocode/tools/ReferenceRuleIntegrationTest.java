package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 挑对象的引用筛选与显示名（当前开发库真实读写）。用例改写自老 virtual-table-ref-options-rowvalue.e2e.spec.ts:257-370、
 * data-linkage.service.spec.ts:414-521 与 reffilter-operators.J327.spec.ts，覆盖
 * B1、B2、B4、B10–B13、B23、B24、B42。
 */
class ReferenceRuleIntegrationTest {
    private FieldRuleFixture f;
    private DataCenter.Definition account;
    private DataCenter.Definition payment;
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
        account =
                f.object(
                        "account",
                        List.of(
                                field("company", "所属公司", "TEXT"),
                                field("bank", "口座名", "TEXT"),
                                field("stock", "库存", "INTEGER"),
                                field("balance", "余额", "MONEY", 18, 0),
                                field("opened", "开户日期", "DATE"),
                                field("memo", "备注", "TEXT")),
                        Map.of(),
                        List.of(),
                        List.of());
        payment =
                f.object(
                        "payment",
                        List.of(field("company", "公司", "TEXT")),
                        Map.of(),
                        List.of(reference("account", account)),
                        List.of());
        reference = relationField(payment, "account");
        app = f.app(account, payment);
        // 两家公司都有口座，另有一行所属公司为空（防「空值恰好也算命中」）。
        row("甲-三菱", "甲公司", 9, "999", "2026-01-15");
        row("甲-瑞穗", "甲公司", 50, "1000", "2026-04-01");
        row("甲-住信", "甲公司", 100, "5000", "2026-06-01");
        row("乙-三井", "乙公司", 10, "100", "2026-02-01");
        row("乙-りそな", "乙公司", 5, "200", "2026-07-01");
        row("无主口座", null, 1, "1", "2026-05-01");
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    private void row(String name, String company, int stock, String balance, String opened) {
        var values =
                values(
                        id(account, "name"), name,
                        id(account, "bank"), name + "银行",
                        id(account, "stock"), stock,
                        id(account, "balance"), balance,
                        id(account, "opened"), opened);
        if (company != null) values.put(id(account, "company"), company);
        ids.put(name, f.save(app, account, values).id());
    }

    private void filter(FieldRules rules) {
        payment = FieldRuleFixture.rules(payment, reference, rules);
    }

    private FieldRules byCompany() {
        return FieldRuleFixture.filter(
                null, formField(id(account, "company"), "eq", id(payment, "company")));
    }

    private SelectionFields.Result candidates(Map<String, Object> formValues) {
        return f.selection(app, payment, reference, formValues, List.of());
    }

    private static List<String> labels(SelectionFields.Result result) {
        return result.options().stream().map(SelectionFields.Option::label).sorted().toList();
    }

    /** 夹具自检：口座表真的有 6 行、甲 3 / 乙 2，且不筛时候选是全量（下文「只剩几行」不是空对空）。 */
    @Test
    void fixtureHasBothCompanies() {
        var all = candidates(Map.of());
        assertThat(all.total()).isEqualTo(6);
        assertThat(all.ruleState()).isNull();
    }

    /** B1：依赖的当前字段没值时候选恒为空，不退化成全量；点名在等哪个字段。 */
    @Test
    void pendingWhenCompanyMissing() {
        filter(byCompany());
        var r = candidates(Map.of());
        assertThat(r.ruleState()).isEqualTo("PENDING_ROW_VALUE");
        assertThat(r.options()).isEmpty();
        assertThat(r.total()).isZero();
        assertThat(r.pendingFields()).containsExactly(id(payment, "company"));
        assertThat(r.ruleMessage()).contains("公司");
        var unknown = candidates(Map.of("ffffffff-0000", "甲公司"));
        assertThat(unknown.ruleState()).as("传本对象没有的字段仍 PENDING").isEqualTo("PENDING_ROW_VALUE");
        assertThat(unknown.options()).isEmpty();
    }

    /** B2：空串算没值，不拿空串去筛（否则会命中「无主口座」）。 */
    @Test
    void emptyStringIsPending() {
        filter(byCompany());
        var r = candidates(Map.of(id(payment, "company"), ""));
        assertThat(r.ruleState()).isEqualTo("PENDING_ROW_VALUE");
        assertThat(r.options()).isEmpty();
    }

    /** B10：换一个依赖值，结果跟着换（证明真的在筛）；total 是同一 where 上的全集计数。 */
    @Test
    void resultFollowsDependency() {
        filter(byCompany());
        var a = candidates(Map.of(id(payment, "company"), "甲公司"));
        assertThat(a.ruleState()).isNull();
        assertThat(labels(a)).containsExactly("甲-三菱", "甲-住信", "甲-瑞穗");
        assertThat(a.total()).isEqualTo(3);
        var b = candidates(Map.of(id(payment, "company"), "乙公司"));
        assertThat(labels(b)).containsExactly("乙-りそな", "乙-三井");
        assertThat(b.total()).isEqualTo(2);
    }

    /** B4：多值当前字段取并集，混入的空串被剔除；并集只在单条条件内，条件之间仍是 AND。 */
    @Test
    void multiValueUnion() {
        filter(byCompany());
        var union = candidates(Map.of(id(payment, "company"), List.of("甲公司", "乙公司", "")));
        assertThat(union.total()).isEqualTo(5);
        filter(
                FieldRuleFixture.filter(
                        null,
                        formField(id(account, "company"), "eq", id(payment, "company")),
                        constant(id(account, "stock"), "gte", 10)));
        var and = candidates(Map.of(id(payment, "company"), List.of("甲公司", "乙公司")));
        assertThat(labels(and)).containsExactly("乙-三井", "甲-住信", "甲-瑞穗");
        var allEmpty = candidates(Map.of(id(payment, "company"), List.of("", "")));
        assertThat(allEmpty.ruleState()).isEqualTo("PENDING_ROW_VALUE");
    }

    /** B11：纯常量筛选照常生效，不受 formValues 影响，也不会被误判成 PENDING。 */
    @Test
    void constantFilterIgnoresFormValues() {
        filter(FieldRuleFixture.filter(null, constant(id(account, "company"), "eq", "甲公司")));
        for (var values :
                List.<Map<String, Object>>of(Map.of(), Map.of(id(payment, "company"), "乙公司"))) {
            var r = candidates(values);
            assertThat(r.ruleState()).isNull();
            assertThat(r.pendingFields()).isNull();
            assertThat(labels(r)).containsExactly("甲-三菱", "甲-住信", "甲-瑞穗");
        }
    }

    /** B12：数值比较按数值（9 不大于 10），不按字典序。 */
    @Test
    void numericCompareIsNumeric() {
        filter(FieldRuleFixture.filter(null, constant(id(account, "stock"), "gt", "10")));
        assertThat(labels(candidates(Map.of()))).containsExactly("甲-住信", "甲-瑞穗");
        filter(
                FieldRuleFixture.filter(
                        null,
                        constant(id(account, "stock"), "gte", 10),
                        constant(id(account, "stock"), "lte", 60)));
        assertThat(labels(candidates(Map.of()))).containsExactly("乙-三井", "甲-瑞穗");
    }

    /** B12：日期按时间比较，between 取闭区间。 */
    @Test
    void dateBetween() {
        filter(
                FieldRuleFixture.filter(
                        null,
                        constant(
                                id(account, "opened"),
                                "between",
                                List.of("2026-03-01", "2026-06-01"))));
        assertThat(labels(candidates(Map.of()))).containsExactly("无主口座", "甲-住信", "甲-瑞穗");
        filter(FieldRuleFixture.filter(null, constant(id(account, "opened"), "gt", "2026-06-01")));
        assertThat(labels(candidates(Map.of()))).containsExactly("乙-りそな");
    }

    /** B13：MONEY 是 numeric 真列，比较正确执行（老系统 J327 此处 fail-closed，这里改为正向断言）。 */
    @Test
    void moneyCompareOnRealColumn() {
        filter(FieldRuleFixture.filter(null, constant(id(account, "balance"), "gt", "1000")));
        assertThat(labels(candidates(Map.of()))).containsExactly("甲-住信");
    }

    /** B14（数据库侧）：like 的 % 按字面匹配。 */
    @Test
    void likeMatchesPercentLiterally() {
        f.save(app, account, values(id(account, "name"), "打折50%口座", id(account, "memo"), "50%"));
        f.save(app, account, values(id(account, "name"), "五百口座", id(account, "memo"), "500"));
        filter(FieldRuleFixture.filter(null, constant(id(account, "memo"), "like", "50%")));
        assertThat(labels(candidates(Map.of()))).containsExactly("打折50%口座");
    }

    /** B23：筛过了但 0 行仍是 APPLIED（正确答案），不是 PENDING、也不是全量。 */
    @Test
    void zeroRowsApplied() {
        filter(byCompany());
        var r = candidates(Map.of(id(payment, "company"), "丙公司"));
        assertThat(r.ruleState()).isNull();
        assertThat(r.pendingFields()).isNull();
        assertThat(r.options()).isEmpty();
        assertThat(r.total()).isZero();
    }

    /** B24：value 是行 id，label 是显示名字段；显示名为空或不可读时回落标题模板。 */
    @Test
    void valueIsIdLabelIsField() {
        f.save(app, account, values(id(account, "name"), "没填口座名", id(account, "company"), "丁公司"));
        filter(
                FieldRuleFixture.filter(
                        id(account, "bank"),
                        formField(id(account, "company"), "eq", id(payment, "company"))));
        var r =
                f.selection(
                        app,
                        payment,
                        reference,
                        Map.of(id(payment, "company"), "甲公司"),
                        List.of(ids.get("甲-三菱")));
        assertThat(r.options())
                .extracting(SelectionFields.Option::label)
                .containsExactlyInAnyOrder("甲-三菱银行", "甲-瑞穗银行", "甲-住信银行");
        assertThat(r.options())
                .filteredOn(o -> o.label().equals("甲-三菱银行"))
                .singleElement()
                .satisfies(o -> assertThat(o.value()).isEqualTo(ids.get("甲-三菱")));
        assertThat(r.selected().getFirst().label()).isEqualTo("甲-三菱银行");
        var blank = candidates(Map.of(id(payment, "company"), "丁公司"));
        assertThat(blank.options())
                .extracting(SelectionFields.Option::label)
                .containsExactly("没填口座名");
    }

    /** 已选值不满足筛选：照常回显名称但标 disabled（界面显示「不符合当前筛选」）。 */
    @Test
    void selectedOutsideFilterIsDisabled() {
        filter(byCompany());
        var r =
                f.selection(
                        app,
                        payment,
                        reference,
                        Map.of(id(payment, "company"), "乙公司"),
                        List.of(ids.get("甲-三菱"), ids.get("乙-三井")));
        assertThat(r.selected()).hasSize(2);
        assertThat(r.selected().get(0).disabled()).isTrue();
        assertThat(r.selected().get(1).disabled()).isFalse();
        var pending = f.selection(app, payment, reference, Map.of(), List.of(ids.get("甲-三菱")));
        assertThat(pending.selected())
                .singleElement()
                .satisfies(o -> assertThat(o.label()).isEqualTo("甲-三菱"));
    }

    /** B42：表单限定视图与对象筛选按 AND 叠加，只能更严。 */
    @Test
    void viewAndRuleIntersect() {
        var mapper = servicesContext.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        var view =
                new ApplicationUi.View(
                        account.objectId(),
                        List.of(id(account, "name")),
                        Map.of(),
                        null,
                        false,
                        10,
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        new ViewQueryOptions(
                                List.of(new DataScope.Condition(id(account, "stock"), "gte", 10)),
                                null,
                                null));
        var form =
                new ApplicationUi.Form(
                        payment.objectId(),
                        List.of(
                                new ApplicationUi.Node(
                                        "company_node",
                                        "FIELD",
                                        id(payment, "company"),
                                        null,
                                        null,
                                        null,
                                        List.of()),
                                new ApplicationUi.Node(
                                        "ref_node",
                                        "FIELD",
                                        reference,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        new ApplicationUi.FieldPresentation(
                                                null,
                                                null,
                                                null,
                                                false,
                                                new SelectionFields.Presentation(
                                                        "SELECT",
                                                        List.of(),
                                                        false,
                                                        null,
                                                        null,
                                                        null,
                                                        "limit_view")))),
                        List.of());
        var before = f.applications.get(app);
        var saved =
                f.applications.save(
                        new ApplicationCenter.Save(
                                app,
                                before.application().revision(),
                                before.application().code(),
                                before.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        before.draft().objects(),
                                        List.of(
                                                new ApplicationCenter.Resource(
                                                        "rule_form",
                                                        "FORM",
                                                        "rule_form",
                                                        "规则表单",
                                                        mapper.convertValue(form, Map.class)),
                                                new ApplicationCenter.Resource(
                                                        "limit_view",
                                                        "VIEW",
                                                        "limit_view",
                                                        "限定视图",
                                                        mapper.convertValue(view, Map.class))))),
                        10001);
        f.applications.publish(
                new ApplicationCenter.Revision(app, saved.application().revision(), "视图叠加"), 10001);
        filter(byCompany());
        var r =
                f.selection(
                        app,
                        payment,
                        null,
                        reference,
                        Map.of(id(payment, "company"), "甲公司"),
                        List.of(),
                        "rule_form");
        assertThat(labels(r)).containsExactly("甲-住信", "甲-瑞穗");
        var none =
                f.selection(
                        app,
                        payment,
                        null,
                        reference,
                        Map.of(id(payment, "company"), "丙公司"),
                        List.of(),
                        "rule_form");
        assertThat(none.options()).isEmpty();
        assertThat(none.ruleState()).isNull();
    }

    /** 引用筛选范围与复核口径：idsWithinScope 只返回既在筛选内又在目标 READ 范围内的 ID。 */
    @Test
    void idsWithinScopeHonoursFilter() {
        filter(byCompany());
        var evaluator =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.runtime.service.rules.FieldRuleService.class);
        var relation =
                payment.relations().stream()
                        .filter(r -> r.fieldId().equals(reference))
                        .findFirst()
                        .orElseThrow();
        var ctx =
                new com.lingan.ucp.nocode.runtime.service.rules.RuleContext(
                        app, payment, 10001, null, null);
        var scope =
                evaluator.referenceScope(
                        ctx, null, relation, Map.of(id(payment, "company"), "乙公司"));
        assertThat(scope.state()).isEqualTo("APPLIED");
        assertThat(
                        evaluator.idsWithinScope(
                                ctx, relation, scope, List.of(ids.get("甲-三菱"), ids.get("乙-三井"))))
                .containsExactly(ids.get("乙-三井"));
        var pending = evaluator.referenceScope(ctx, null, relation, Map.of());
        assertThat(pending.state()).isEqualTo("PENDING_ROW_VALUE");
        assertThat(pending.conditions()).isNull();
        assertThat(evaluator.idsWithinScope(ctx, relation, pending, List.of(ids.get("乙-三井"))))
                .isEmpty();
    }
}
