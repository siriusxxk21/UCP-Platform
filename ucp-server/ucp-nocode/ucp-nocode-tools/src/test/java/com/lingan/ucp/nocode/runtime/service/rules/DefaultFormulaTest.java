package com.lingan.ucp.nocode.runtime.service.rules;

import static com.lingan.ucp.nocode.runtime.service.rules.RuleFixtures.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.FieldRules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.*;

/**
 * 公式默认值的保存时执行（15.3.2 取整；B31 按 2026-09-29 只读口径改写）。求值复用对象公式语法与求值器；公式默认值一律只读：新建时求值，
 * 更新时任一依赖变化或客户端提交该字段就重算，忽略客户端值；依赖为空时写空、不写 0。
 *
 * <p>路 A 的发布期校验（金额公式禁 round 等）不在本类。
 */
class DefaultFormulaTest {
    private final ScriptedFieldRuleEvaluator evaluator = new ScriptedFieldRuleEvaluator();
    private final FieldRuleEnforcer enforcer = enforcer(evaluator);
    private static final Set<String> ALL = Set.of("price", "qty", "total", "ratio", "memo");

    private Map<String, Object> create(Map<String, FieldRules> rules, Map<String, Object> input) {
        return enforcer.prepare(
                context(definition(rules, Map.of())), true, Map.of(), input, input.keySet(), ALL);
    }

    @Test
    void onceOnCreate() {
        var result =
                create(
                        Map.of("total", formula("price * qty", null)),
                        values("price", "10.5", "qty", 3));
        // 31.5 按缺省 FLOOR 取整。
        assertThat(result).containsEntry("total", "31");
        assertThat(evaluator.calls).isEmpty();
    }

    private Map<String, Object> update(
            Map<String, Object> previous, Map<String, Object> input, Set<String> authorized) {
        return enforcer.prepare(
                context(definition(Map.of("total", formula("price * qty", null)), Map.of())),
                false,
                previous,
                input,
                input.keySet(),
                authorized);
    }

    /** 2026-09-29（取代 B31「更新时不跟随」）：依赖变化即重算。 */
    @Test
    void recomputedOnUpdateWhenDependencyChanges() {
        var result = update(values("price", "10", "qty", 1, "total", "10"), values("qty", 2), ALL);
        assertThat(result).containsEntry("total", "20");
    }

    @Test
    void notRecomputedWhenDependenciesUnchanged() {
        var result =
                update(
                        values("price", "10", "qty", 1, "total", "10", "memo", "旧"),
                        values("memo", "只改备注", "qty", "1.0"),
                        ALL);
        assertThat(result).doesNotContainKey("total");
    }

    /** 客户端伪造的公式值不被接受：提交该字段即按服务端合并值重算。 */
    @Test
    void forgedValueOnUpdateIsRecomputed() {
        var result =
                update(values("price", "10", "qty", 3, "total", "30"), values("total", "999"), ALL);
        assertThat(result).containsEntry("total", "30");
    }

    /** 依赖被清空时结果为空：写空并清掉旧值，不写 0。 */
    @Test
    void clearedDependencyClearsStoredValue() {
        var result =
                update(values("price", "10", "qty", 3, "total", "30"), values("qty", null), ALL);
        assertThat(result).containsEntry("total", null);
    }

    /** 服务端受控赋值仍受字段写权限约束：无权时不写、保持旧值。 */
    @Test
    void noWritePermissionKeepsOldValue() {
        var result =
                update(
                        values("price", "10", "qty", 1, "total", "10"),
                        values("qty", 2),
                        Set.of("price", "qty"));
        assertThat(result).doesNotContainKey("total");
    }

    /** 2026-09-29（取代 B31「用户已填不覆盖」）：新建时客户端值同样被覆盖。 */
    @Test
    void clientValueIgnoredOnCreate() {
        var result =
                create(
                        Map.of("total", formula("price * qty", null)),
                        values("price", "10", "qty", 3, "total", "100"));
        assertThat(result).containsEntry("total", "30");
    }

    @Test
    void emptyStringCountsAsNotFilled() {
        var result =
                create(
                        Map.of("total", formula("price * qty", null)),
                        values("price", "10", "qty", 3, "total", ""));
        assertThat(result).containsEntry("total", "30");
    }

    /** 依赖为空时写空（不写 0）；客户端提交的值同样被清掉。 */
    @Test
    void nullWrittenAsNullNotZero() {
        var result = create(Map.of("total", formula("price * qty", null)), values("price", "10"));
        assertThat(result).containsEntry("total", null);
        var blank =
                create(
                        Map.of("total", formula("price * qty", null)),
                        values("price", "10", "qty", "", "total", "5"));
        assertThat(blank).containsEntry("total", null);
    }

    @ParameterizedTest
    @CsvSource({
        "1.5, HALF_UP, 2",
        "1.5, FLOOR, 1",
        "1.5, DOWN, 1",
        "1.5, , 1",
        "-1.5, HALF_UP, -2",
        "-1.5, FLOOR, -2",
        "-1.5, DOWN, -1",
        "2.5, HALF_UP, 3",
        "2.5, FLOOR, 2",
        "2.5, DOWN, 2",
        "7, FLOOR, 7"
    })
    void moneyResultRoundedByConfig(String price, String rounding, String expected) {
        var result =
                create(
                        Map.of("total", formula("price * qty", rounding)),
                        values("price", price, "qty", 1));
        assertThat(result).containsEntry("total", expected);
    }

    /**
     * 除法只保留 16 位中间小数：1000 / 3 * 3 = 999.9999999999999999。取整前先归到 10 位小数， 「向下取整」「去掉小数」不能因此少 1
     * 円；真有小数的结果照常取整，不被多进一位。
     */
    @ParameterizedTest
    @CsvSource({
        "price / qty * qty, 1000, 3, FLOOR, 1000",
        "price / qty * qty, 1000, 3, DOWN, 1000",
        "price / qty * qty, 1000, 3, HALF_UP, 1000",
        "price / qty * qty, 1000, 3, , 1000",
        "price / qty * qty, -1000, 3, FLOOR, -1000",
        "price / qty * qty, -1000, 3, DOWN, -1000",
        "price / qty, 1000, 3, FLOOR, 333",
        "price / qty, 2000, 3, FLOOR, 666",
        "price / qty, 2000, 3, HALF_UP, 667",
        "price / qty, -1000, 3, FLOOR, -334",
        "price / qty, -1000, 3, DOWN, -333"
    })
    void divisionTailDoesNotLoseOneYen(
            String expression, String price, int qty, String rounding, String expected) {
        var result =
                create(
                        Map.of("total", formula(expression, rounding)),
                        values("price", price, "qty", qty));
        assertThat(result).containsEntry("total", expected);
    }

    @Test
    void unknownRoundingRejected() {
        assertThatThrownBy(
                        () ->
                                create(
                                        Map.of("total", formula("price * qty", "CEILING")),
                                        values("price", "1.5", "qty", 1)))
                .hasMessage("字段「total」的取整方式「CEILING」无效，可选：四舍五入 / 向下取整 / 去掉小数");
    }

    @Test
    void nonMoneyKeepsDecimals() {
        var result = create(Map.of("ratio", formula("price / 4", null)), values("price", "10.5"));
        assertThat(result).containsEntry("ratio", "2.625");
    }

    @Test
    void nonMoneyBeyondScaleIsRejectedNotTruncated() {
        assertThatThrownBy(
                        () ->
                                create(
                                        Map.of("ratio", formula("price / 3", null)),
                                        values("price", "1")))
                .hasMessageContaining("超出字段小数位数");
    }

    @Test
    void textTargetReceivesFormulaText() {
        var result = create(Map.of("memo", formula("'合计' || qty", null)), values("qty", 3));
        assertThat(result).containsEntry("memo", "合计3");
    }

    @Test
    void formulaSeesForcedReadonlyLinkageValue() {
        evaluator.linkage("qty", v -> ScriptedFieldRuleEvaluator.applied(4));
        var result =
                create(
                        Map.of(
                                "qty", linkage(true, "company"),
                                "total", formula("price * qty", null)),
                        values("price", "2", "company", "甲"));
        assertThat(result).containsEntry("qty", 4).containsEntry("total", "8");
    }

    @Test
    void newRowFormulaUsesRowCodeBeforeMasterCode() {
        var rules = Map.of("amount", formula("price * line_qty * qty", null));
        var ctx = context(definition(Map.of(), rules));
        var detail = ctx.definition().details().getFirst();
        // 明细本行有编码 price（d_price），优先于主表 price；qty 只在主表。
        var result =
                enforcer.prepareRow(
                        ctx,
                        detail,
                        "k1",
                        null,
                        true,
                        Map.of(),
                        values("d_price", "2.5", "line_qty", 3),
                        values(),
                        values("price", "100", "qty", 2),
                        Set.of("d_price", "line_qty"));
        assertThat(result).containsEntry("amount", "15");
    }

    /** 2026-09-29（取代「复制行保留值、已有行不跟随」）：复制行按本行值重算，已有行依赖变化即重算、不变不动。 */
    @Test
    void rowFormulaRecomputedOnCopyAndDependencyChange() {
        var rules = Map.of("amount", formula("price * line_qty", null));
        var ctx = context(definition(Map.of(), rules));
        var detail = ctx.definition().details().getFirst();
        var copied =
                enforcer.prepareRow(
                        ctx,
                        detail,
                        "copy",
                        null,
                        true,
                        Map.of(),
                        values("d_price", "2", "line_qty", 3, "amount", "999"),
                        values(),
                        values(),
                        Set.of("d_price", "line_qty", "amount"));
        assertThat(copied).containsEntry("amount", "6");
        var existing =
                enforcer.prepareRow(
                        ctx,
                        detail,
                        "row-1",
                        "1",
                        false,
                        values("d_price", "2", "line_qty", 3, "amount", "6"),
                        values("line_qty", 4),
                        values(),
                        values(),
                        Set.of("line_qty"));
        assertThat(existing).containsEntry("amount", "8");
        var untouched =
                enforcer.prepareRow(
                        ctx,
                        detail,
                        "row-1",
                        "1",
                        false,
                        values("d_price", "2", "line_qty", 3, "amount", "6", "note", "旧"),
                        values("note", "只改备注"),
                        values(),
                        values(),
                        Set.of("note"));
        assertThat(untouched).doesNotContainKey("amount");
    }

    @Test
    void rowFormulaRecomputedWhenMasterDependencyChanges() {
        var rules = Map.of("amount", formula("line_qty * qty", null));
        var ctx = context(definition(Map.of(), rules));
        var detail = ctx.definition().details().getFirst();
        var result =
                enforcer.prepareRow(
                        ctx,
                        detail,
                        "row-1",
                        "1",
                        false,
                        values("line_qty", 3, "amount", "6"),
                        values(),
                        values("qty", 2),
                        values("qty", 5),
                        Set.of());
        assertThat(result).containsEntry("amount", "15");
    }

    /** 明细公式引用只读联动：联动强制写入后按最终值重算（已有行由主表依赖触发）。 */
    @Test
    void rowFormulaSeesForcedLinkageOnExistingRow() {
        evaluator.linkage(
                "line_qty",
                v -> ScriptedFieldRuleEvaluator.applied("甲".equals(v.get("company")) ? 2 : 7));
        var rules =
                Map.of(
                        "line_qty", linkage(true, "company"),
                        "amount", formula("line_qty * 10", null));
        var ctx = context(definition(Map.of(), rules));
        var result =
                enforcer.prepareRow(
                        ctx,
                        ctx.definition().details().getFirst(),
                        "row-1",
                        "1",
                        false,
                        values("line_qty", 2, "amount", "20"),
                        values(),
                        values("company", "甲"),
                        values("company", "乙"),
                        Set.of());
        assertThat(result).containsEntry("line_qty", 7).containsEntry("amount", "70");
    }
}
