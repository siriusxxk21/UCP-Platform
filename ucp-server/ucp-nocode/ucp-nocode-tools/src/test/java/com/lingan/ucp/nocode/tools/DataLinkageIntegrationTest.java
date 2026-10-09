package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.runtime.dal.mapper.RecordMapper;
import com.lingan.ucp.nocode.runtime.service.record.RecordSelectionSupport;

import org.junit.jupiter.api.*;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

/**
 * 数据联动运行期求值（当前开发库真实读写）。用例改写自老 data-linkage.service.spec.ts:239-412 与 linkage-value.spec.ts， 覆盖设计稿 8
 * 章 B4、B8、B15–B17、B37–B40 与 15.6 B62。
 */
class DataLinkageIntegrationTest {
    private FieldRuleFixture f;
    private DataCenter.Definition flow;
    private DataCenter.Definition voucher;
    private String app;

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
        flow =
                f.object(
                        "flow",
                        List.of(
                                field("company", "公司", "TEXT"),
                                field("bank", "银行", "TEXT"),
                                field("amount", "金额", "MONEY", 18, 2),
                                field("qty", "数量", "INTEGER"),
                                field("price", "单价", "DECIMAL", 20, 4),
                                field("total", "总价", "FORMULA")),
                        Map.of("total", formula("qty * price", "DECIMAL")),
                        List.of(),
                        List.of());
        voucher =
                f.object(
                        "voucher",
                        List.of(
                                field("company", "公司", "TEXT"),
                                field("bank", "银行名", "TEXT"),
                                field("first_bank", "首行银行", "TEXT"),
                                field("amount", "合计", "MONEY", 18, 0),
                                field("total", "总价", "DECIMAL", 20, 4),
                                field("qty", "数量", "INTEGER"),
                                field("price", "单价", "DECIMAL", 20, 4)),
                        Map.of(),
                        List.of(),
                        List.of());
        app = f.app(flow, voucher);
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    private ApplicationRecords.Row flowRow(String company, String bank, Object amount) {
        return f.save(
                app,
                flow,
                values(
                        id(flow, "name"),
                        bank,
                        id(flow, "company"),
                        company,
                        id(flow, "bank"),
                        bank,
                        id(flow, "amount"),
                        amount));
    }

    private void rule(String targetCode, FieldRules rules) {
        voucher = FieldRuleFixture.rules(voucher, id(voucher, targetCode), rules);
    }

    private FieldRules.Evaluation evaluate(Map<String, Object> values, long actor) {
        return f.evaluate(app, voucher, values, List.of(), List.of(), null, actor);
    }

    private FieldRules.Result evaluate(String targetCode, Map<String, Object> values) {
        return result(evaluate(values, 10001), id(voucher, targetCode));
    }

    private FieldRules.Condition byCompany() {
        return formField(id(flow, "company"), "eq", id(voucher, "company"));
    }

    /** B8：条件为空表示命中全部行，是合法配置；拼接按 create_time、主键升序。 */
    @Test
    void emptyConditionsMatchAll() {
        flowRow("甲", "三菱", "1");
        flowRow("甲", "みずほ", "2");
        flowRow("乙", "三井住友", "3");
        rule("bank", linkage(flow, id(flow, "bank"), null, List.of()));
        var r = evaluate("bank", Map.of());
        assertThat(r.state()).isEqualTo("APPLIED");
        assertThat(r.kind()).isEqualTo("LINKAGE");
        assertThat(r.value()).isEqualTo("三菱,みずほ,三井住友");
        assertThat(r.matchedRows()).isEqualTo(3);
    }

    /** 老 spec :240：两条条件都满足才命中（AND 变 OR 会红）；换依赖值结果跟着换。 */
    @Test
    void conditionsAreAnd() {
        flowRow("甲", "三菱", "100");
        flowRow("甲", "みずほ", "5000");
        flowRow("乙", "三井住友", "5000");
        rule(
                "bank",
                linkage(
                        flow,
                        id(flow, "bank"),
                        "CONCAT",
                        List.of(byCompany(), constant(id(flow, "amount"), "gte", "1000"))));
        assertThat(evaluate("bank", Map.of(id(voucher, "company"), "甲")).value()).isEqualTo("みずほ");
        assertThat(evaluate("bank", Map.of(id(voucher, "company"), "乙")).value()).isEqualTo("三井住友");
    }

    /** B4：多值当前字段在条件内取并集（eq 改写 in），不只取第一个值。 */
    @Test
    void multiValueEqUnion() {
        flowRow("甲", "三菱", "1");
        flowRow("甲", "みずほ", "1");
        flowRow("乙", "三井住友", "1");
        flowRow("丙", "りそな", "1");
        rule("bank", linkage(flow, id(flow, "bank"), "CONCAT", List.of(byCompany())));
        var r = evaluate("bank", Map.of(id(voucher, "company"), List.of("甲", "乙", "")));
        assertThat(r.state()).isEqualTo("APPLIED");
        assertThat(r.value()).isEqualTo("三菱,みずほ,三井住友");
        assertThat(r.matchedRows()).isEqualTo(3);
    }

    /** B15：多行排序固定为 create_time 升序、主键兜底；与插入顺序（主键）不同时以 create_time 为准。 */
    @Test
    void firstRowIsEarliest() {
        flowRow("甲", "先建", "1");
        flowRow("甲", "次建", "1");
        var last = flowRow("甲", "后建但时间最早", "1");
        jdbc.update(
                "UPDATE public.\""
                        + flow.tableName()
                        + "\" SET create_time = (SELECT min(create_time) FROM public.\""
                        + flow.tableName()
                        + "\") - interval '1 day' WHERE id::text = ?",
                last.id());
        rule("first_bank", linkage(flow, id(flow, "bank"), "FIRST", List.of(byCompany())));
        var r = evaluate("first_bank", Map.of(id(voucher, "company"), "甲"));
        assertThat(r.value()).isEqualTo("后建但时间最早");
        assertThat(r.matchedRows()).isEqualTo(3);
    }

    /** B16：上限 5000，多取一行判截断；截断只对拼接、求和致命，取第一行照常、报错档照常报错。 */
    @Test
    void tooManyRowsOnlyForConcatAndSum() {
        jdbc.update(
                "INSERT INTO public.\""
                        + flow.tableName()
                        + "\"(name, company, bank, amount, creator, updater, create_time,"
                        + " update_time, deleted) SELECT 'r' || g, '满', 'b' || g, 1, '10001',"
                        + " '10001', clock_timestamp() + g * interval '1 millisecond',"
                        + " clock_timestamp(), 0 FROM generate_series(1, 5001) g");
        var company = Map.<String, Object>of(id(voucher, "company"), "满");
        rule("bank", linkage(flow, id(flow, "bank"), "CONCAT", List.of(byCompany())));
        var concat = evaluate("bank", company);
        assertThat(concat.state()).isEqualTo("TOO_MANY_ROWS");
        assertThat(concat.value()).isNull();
        assertThat(concat.matchedRows()).isEqualTo(5000);
        rule(
                "amount",
                new FieldRules(
                        null,
                        new FieldRules.Linkage(
                                flow.objectId(),
                                List.of(byCompany()),
                                id(flow, "amount"),
                                "SUM",
                                false,
                                null,
                                null),
                        null,
                        null,
                        null,
                        null));
        assertThat(evaluate("amount", company).state()).isEqualTo("TOO_MANY_ROWS");
        rule("bank", linkage(flow, id(flow, "bank"), "FIRST", List.of(byCompany())));
        var first = evaluate("bank", company);
        assertThat(first.state()).isEqualTo("APPLIED");
        assertThat(first.value()).isEqualTo("b1");
        rule("bank", linkage(flow, id(flow, "bank"), "ERROR", List.of(byCompany())));
        assertThat(evaluate("bank", company).state()).isEqualTo("MULTI_ROW_ERROR");
    }

    /** B17：0 行返回 NO_MATCH，不给空串也不给 0。只读按配置（2026-09-29，取代「只在 APPLIED 时为真」）：未命中也只读、值为空。 */
    @Test
    void noMatch() {
        flowRow("甲", "三菱", "1");
        rule(
                "bank",
                new FieldRules(
                        null,
                        new FieldRules.Linkage(
                                flow.objectId(),
                                List.of(byCompany()),
                                id(flow, "bank"),
                                null,
                                true,
                                null,
                                null),
                        null,
                        null,
                        null,
                        null));
        var none = evaluate("bank", Map.of(id(voucher, "company"), "不存在"));
        assertThat(none.state()).isEqualTo("NO_MATCH");
        assertThat(none.value()).isNull();
        assertThat(none.matchedRows()).isZero();
        assertThat(none.readOnly()).isTrue();
        assertThat(none.message()).isNotBlank();
        var hit = evaluate("bank", Map.of(id(voucher, "company"), "甲"));
        assertThat(hit.state()).isEqualTo("APPLIED");
        assertThat(hit.readOnly()).isTrue();
    }

    /** 2026-09-29：readOnly 为 null 按只读（依赖未填也只读）；显式 false 仍可手改，命中时也不只读。 */
    @Test
    void readOnlyDefaultsToTrueAndExplicitFalseStaysEditable() {
        flowRow("甲", "三菱", "1");
        rule(
                "bank",
                new FieldRules(
                        null,
                        new FieldRules.Linkage(
                                flow.objectId(),
                                List.of(byCompany()),
                                id(flow, "bank"),
                                null,
                                null,
                                null,
                                null),
                        null,
                        null,
                        null,
                        null));
        var hit = evaluate("bank", Map.of(id(voucher, "company"), "甲"));
        assertThat(hit.state()).isEqualTo("APPLIED");
        assertThat(hit.readOnly()).isTrue();
        var pending = evaluate("bank", Map.of());
        assertThat(pending.state()).isEqualTo("PENDING_ROW_VALUE");
        assertThat(pending.readOnly()).isTrue();
        assertThat(pending.value()).isNull();
        rule("bank", linkage(flow, id(flow, "bank"), "FIRST", List.of(byCompany())));
        var editable = evaluate("bank", Map.of(id(voucher, "company"), "甲"));
        assertThat(editable.state()).isEqualTo("APPLIED");
        assertThat(editable.readOnly()).isFalse();
    }

    /** B20′/B43 端到端：金额求和定点相加后按目标的取整方式取整。 */
    @Test
    void moneySumRoundedByTarget() {
        flowRow("甲", "a", "100.50");
        flowRow("甲", "b", "200.40");
        flowRow("甲", "c", "300");
        for (var expected : Map.of("FLOOR", "600", "HALF_UP", "601", "DOWN", "600").entrySet()) {
            rule(
                    "amount",
                    new FieldRules(
                            null,
                            new FieldRules.Linkage(
                                    flow.objectId(),
                                    List.of(byCompany()),
                                    id(flow, "amount"),
                                    "SUM",
                                    false,
                                    null,
                                    null),
                            null,
                            "FLOOR".equals(expected.getKey()) ? null : expected.getKey(),
                            null,
                            null));
            assertThat(evaluate("amount", Map.of(id(voucher, "company"), "甲")).value())
                    .as(expected.getKey())
                    .isEqualTo(expected.getValue());
        }
    }

    /** B38：同一「来源 + 已解析条件」在一次请求内只查一次；不同条件各查一次。 */
    @Test
    void queryCountBound() {
        flowRow("甲", "三菱", "10");
        flowRow("乙", "三井住友", "20");
        rule("bank", linkage(flow, id(flow, "bank"), "CONCAT", List.of(byCompany())));
        rule(
                "amount",
                new FieldRules(
                        null,
                        new FieldRules.Linkage(
                                flow.objectId(),
                                List.of(byCompany()),
                                id(flow, "amount"),
                                "SUM",
                                false,
                                null,
                                null),
                        null,
                        null,
                        null,
                        null));
        rule(
                "first_bank",
                linkage(
                        flow,
                        id(flow, "bank"),
                        "FIRST",
                        List.of(constant(id(flow, "company"), "eq", "乙"))));
        var support = servicesContext.getBean(RecordSelectionSupport.class);
        var original = (RecordMapper) ReflectionTestUtils.getField(support, "records");
        var spy = Mockito.mock(RecordMapper.class, AdditionalAnswers.delegatesTo(original));
        ReflectionTestUtils.setField(support, "records", spy);
        try {
            var evaluation = evaluate(Map.of(id(voucher, "company"), "甲"), 10001);
            assertThat(result(evaluation, id(voucher, "bank")).value()).isEqualTo("三菱");
            assertThat(result(evaluation, id(voucher, "amount")).value()).isEqualTo("10");
            assertThat(result(evaluation, id(voucher, "first_bank")).value()).isEqualTo("三井住友");
            Mockito.verify(spy, Mockito.times(2)).rows(Mockito.any());
        } finally {
            ReflectionTestUtils.setField(support, "records", original);
        }
    }

    /**
     * B39（2026-10 起反转）：数据联动是系统取数，按来源对象的全部记录算，不看操作者在来源对象上的记录范围；成员与创建人算出同一个值。
     * 成员能不能看到结果，按结果字段在本对象上的权限。
     */
    @Test
    void respectsRowScope() {
        flowRow("甲", "创建人的口座", "1");
        rule("bank", linkage(flow, id(flow, "bank"), "CONCAT", List.of(byCompany())));
        var flowFields = new HashSet<String>();
        flow.fields().forEach(v -> flowFields.add(v.id()));
        var voucherFields = new HashSet<String>();
        voucher.fields().forEach(v -> voucherFields.add(v.id()));
        var authorization =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.application.service.authorization
                                .ApplicationAuthorizationService.class);
        authorization.save(
                new ApplicationAuthorization.Save(
                        app,
                        authorization.get(app).revision(),
                        List.of(
                                new ApplicationAuthorization.Member(
                                        "USER",
                                        "20002",
                                        List.of(
                                                new ApplicationAuthorization.ObjectGrant(
                                                        flow.objectId(),
                                                        Set.of("READ", "CREATE"),
                                                        "OWN",
                                                        flowFields,
                                                        flowFields,
                                                        Set.of(),
                                                        Set.of()),
                                                new ApplicationAuthorization.ObjectGrant(
                                                        voucher.objectId(),
                                                        Set.of("READ", "CREATE"),
                                                        "ALL",
                                                        voucherFields,
                                                        voucherFields,
                                                        Set.of(),
                                                        Set.of()))))),
                10001);
        f.save(
                app,
                flow,
                values(
                        id(flow, "name"),
                        "成员",
                        id(flow, "company"),
                        "甲",
                        id(flow, "bank"),
                        "成员自己的口座"),
                20002);
        var member =
                result(evaluate(Map.of(id(voucher, "company"), "甲"), 20002), id(voucher, "bank"));
        assertThat(member.value()).isEqualTo("创建人的口座,成员自己的口座");
        assertThat(member.matchedRows()).isEqualTo(2);
        var owner =
                result(evaluate(Map.of(id(voucher, "company"), "甲"), 10001), id(voucher, "bank"));
        assertThat(owner.matchedRows()).isEqualTo(2);
    }

    /** B40：非 APPLIED 不抛 500，回 state 加 message，不滤掉条目。 */
    @Test
    void failClosedNo500() {
        var outside =
                f.object(
                        "outside",
                        List.of(field("bank", "银行", "TEXT")),
                        Map.of(),
                        List.of(),
                        List.of());
        flowRow("甲", "三菱", "1");
        rule("bank", linkage(outside, id(outside, "bank"), null, List.of()));
        rule("first_bank", linkage(flow, "999999999", null, List.of()));
        rule(
                "amount",
                new FieldRules(
                        null,
                        new FieldRules.Linkage(
                                flow.objectId(),
                                List.of(),
                                id(flow, "amount"),
                                "拼字符串",
                                false,
                                null,
                                null),
                        null,
                        null,
                        null,
                        null));
        rule(
                "total",
                linkage(
                        flow,
                        id(flow, "price"),
                        "FIRST",
                        List.of(constant(id(flow, "amount"), "gt", "不是数"))));
        rule(
                "company",
                linkage(
                        flow,
                        id(flow, "company"),
                        "FIRST",
                        List.of(formField(id(flow, "bank"), "eq", id(voucher, "bank")))));
        var evaluation =
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                        () -> evaluate(Map.of(), 10001));
        Map<String, String> states = new HashMap<>();
        for (var r : evaluation.results()) {
            assertThat(r.value()).isNull();
            assertThat(r.message()).isNotBlank();
            states.put(r.fieldId(), r.state());
        }
        assertThat(states)
                // 来源对象没被应用引用：现在是隐式可读（按最新发布版），不再是「来源表缺失」；这里它没有数据，所以是没匹配到。
                .containsEntry(id(voucher, "bank"), "NO_MATCH")
                .containsEntry(id(voucher, "first_bank"), "SOURCE_FIELD_MISSING")
                .containsEntry(id(voucher, "amount"), "MULTI_ROW_MODE_UNKNOWN")
                .containsEntry(id(voucher, "total"), "CONDITION_UNSUPPORTED")
                .containsEntry(id(voucher, "company"), "PENDING_ROW_VALUE");
        assertThat(result(evaluation, id(voucher, "company")).pendingFields())
                .containsExactly(id(voucher, "bank"));
    }

    /** B62：来源值字段是公式时，先补全计算值再取格。 */
    @Test
    void formulaSourceIsEnriched() {
        f.save(
                app,
                flow,
                values(
                        id(flow, "name"),
                        "单价行",
                        id(flow, "company"),
                        "甲",
                        id(flow, "qty"),
                        3,
                        id(flow, "price"),
                        "2.5"));
        rule(
                "total",
                linkage(
                        flow,
                        id(flow, "total"),
                        "FIRST",
                        List.of(constant(id(flow, "company"), "eq", "甲"))));
        var r = evaluate("total", Map.of());
        assertThat(r.state()).isEqualTo("APPLIED");
        assertThat(new BigDecimal(r.value().toString())).isEqualByComparingTo("7.5");
    }

    /** 公式默认值：新建与编辑都求值（2026-09-29，取代「编辑回 NOT_APPLICABLE」），结果一律只读；依赖为空时在等字段，不写 0；金额按取整方式取整。 */
    @Test
    void defaultFormulaAlsoOnEditAndReadOnly() {
        rule("amount", new FieldRules(null, null, "price * qty", "HALF_UP", null, null));
        var values = Map.<String, Object>of(id(voucher, "price"), "10.5", id(voucher, "qty"), 1);
        assertThat(evaluate("amount", values).value()).isEqualTo("11");
        var editing =
                result(
                        f.runtime.evaluateRules(
                                new FieldRules.EvaluateQuery(
                                        app,
                                        voucher.objectId(),
                                        null,
                                        null,
                                        false,
                                        values,
                                        List.of(),
                                        List.of(),
                                        null),
                                10001),
                        id(voucher, "amount"));
        assertThat(editing.state()).isEqualTo("APPLIED");
        assertThat(editing.value()).isEqualTo("11");
        assertThat(editing.readOnly()).isTrue();
        var pending = evaluate("amount", Map.of(id(voucher, "qty"), 1));
        assertThat(pending.state()).isEqualTo("PENDING_ROW_VALUE");
        assertThat(pending.value()).isNull();
        assertThat(pending.readOnly()).isTrue();
        assertThat(pending.pendingFields()).containsExactly(id(voucher, "price"));
    }

    /** 链式求值：只有可覆盖或只读字段的新值参与下游；changed 只重算其传递闭包（带规则的变化字段自身也在内）。 */
    @Test
    void chainedEvaluationUsesOverridableValues() {
        flowRow("甲", "三菱", "1");
        rule(
                "company",
                linkage(
                        flow,
                        id(flow, "company"),
                        "FIRST",
                        List.of(constant(id(flow, "bank"), "eq", "三菱"))));
        rule("bank", linkage(flow, id(flow, "bank"), "FIRST", List.of(byCompany())));
        var chained =
                f.evaluate(
                        app,
                        voucher,
                        Map.of(),
                        List.of(),
                        List.of(id(voucher, "company"), id(voucher, "bank")),
                        null,
                        10001);
        assertThat(result(chained, id(voucher, "bank")).value()).isEqualTo("三菱");
        var userOwned = f.evaluate(app, voucher, Map.of(), List.of(), List.of(), null, 10001);
        assertThat(result(userOwned, id(voucher, "bank")).state()).isEqualTo("PENDING_ROW_VALUE");
        var onlyDownstream =
                f.evaluate(
                        app,
                        voucher,
                        Map.of(id(voucher, "company"), "甲"),
                        List.of(id(voucher, "company")),
                        List.of(),
                        null,
                        10001);
        assertThat(onlyDownstream.results())
                .extracting(FieldRules.Result::fieldId)
                .containsExactly(id(voucher, "company"), id(voucher, "bank"));
        var unrelated =
                f.evaluate(
                        app,
                        voucher,
                        Map.of(),
                        List.of(id(voucher, "price")),
                        List.of(),
                        null,
                        10001);
        assertThat(unrelated.results()).isEmpty();
    }

    /** 实施裁定：changed 里本身带规则的字段自己也是目标（保存时路 C 靠它指定重算字段）。 */
    @Test
    void changedRuleFieldIsItsOwnTarget() {
        flowRow("甲", "三菱", "1");
        rule("bank", linkage(flow, id(flow, "bank"), "FIRST", List.of(byCompany())));
        var self =
                f.evaluate(
                        app,
                        voucher,
                        Map.of(id(voucher, "company"), "甲"),
                        List.of(id(voucher, "bank")),
                        List.of(),
                        null,
                        10001);
        assertThat(self.results())
                .extracting(FieldRules.Result::fieldId)
                .containsExactly(id(voucher, "bank"));
        assertThat(result(self, id(voucher, "bank")).value()).isEqualTo("三菱");
        var direct =
                servicesContext
                        .getBean(com.lingan.ucp.nocode.runtime.service.rules.FieldRuleService.class)
                        .evaluate(
                                new com.lingan.ucp.nocode.runtime.service.rules.RuleContext(
                                        app, voucher, 10001, null, null),
                                Map.of(id(voucher, "company"), "甲"),
                                Set.of(id(voucher, "bank")),
                                Set.of(),
                                false);
        assertThat(direct)
                .extracting(FieldRules.Result::fieldId)
                .containsExactly(id(voucher, "bank"));
    }
}
