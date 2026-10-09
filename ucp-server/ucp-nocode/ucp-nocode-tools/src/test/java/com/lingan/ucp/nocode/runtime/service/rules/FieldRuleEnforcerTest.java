package com.lingan.ucp.nocode.runtime.service.rules;

import static com.lingan.ucp.nocode.runtime.service.rules.RuleFixtures.*;
import static com.lingan.ucp.nocode.runtime.service.rules.ScriptedFieldRuleEvaluator.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.FieldRules;

import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * 保存时强制的判定逻辑（B29、B30、B41、B54–B58 的纯逻辑部分；B29 按 2026-09-29 只读口径：未取到值强制写空、readOnly 为 null 按只读）；
 * 求值器为脚本替身，写入路径另由集成测试覆盖。
 */
class FieldRuleEnforcerTest {
    private final ScriptedFieldRuleEvaluator evaluator = new ScriptedFieldRuleEvaluator();
    private final FieldRuleEnforcer enforcer = enforcer(evaluator);
    private static final Set<String> ALL =
            Set.of(
                    "name", "company", "bank", "branch", "memo", "price", "qty", "total", "ratio",
                    "account");

    private RuleContext mainRules(Map<String, FieldRules> rules) {
        return context(definition(rules, Map.of()));
    }

    private RuleContext detailRules(Map<String, FieldRules> rules) {
        return context(definition(Map.of(), rules));
    }

    private ScriptedFieldRuleEvaluator bankFromCompany() {
        return evaluator.linkage(
                "bank",
                v ->
                        v.get("company") == null
                                ? pending("company")
                                : applied("银行-" + v.get("company")));
    }

    @Test
    void readonlyApiForgedValueIsOverwrittenByServerRecompute() {
        bankFromCompany();
        var result =
                enforcer.prepare(
                        mainRules(Map.of("bank", linkage(true, "company"))),
                        false,
                        values("company", "甲", "bank", "银行-甲"),
                        values("company", "乙", "bank", "伪造"),
                        Set.of("company", "bank"),
                        ALL);
        assertThat(result).containsEntry("bank", "银行-乙").containsEntry("company", "乙");
    }

    @Test
    void recomputeUsesServerMergedValuesNotClientDeclaredDependencies() {
        bankFromCompany();
        // 客户端只提交伪造的只读值，依赖值必须取库中旧值；求值器收到的 company 是服务端的「甲」。
        var result =
                enforcer.prepare(
                        mainRules(Map.of("bank", linkage(true, "company"))),
                        false,
                        values("company", "甲", "bank", "银行-甲"),
                        values("bank", "伪造"),
                        Set.of("bank"),
                        ALL);
        assertThat(result).containsEntry("bank", "银行-甲");
        assertThat(evaluator.calls("LINKAGE", "bank").getFirst().values())
                .containsEntry("company", "甲");
    }

    /** 2026-09-29 口径（取代 B29「PENDING/NO_MATCH 接受用户值」）：只读联动未取到值时字段为空且不能填，客户端值被清成空。 */
    @Test
    void pendingAndNoMatchForceNullIgnoringUserValue() {
        evaluator.linkage("bank", v -> pending("company"));
        var ctx = mainRules(Map.of("bank", linkage(true, "company")));
        assertThat(enforcer.prepare(ctx, true, Map.of(), values("bank", "手填"), Set.of("bank"), ALL))
                .containsEntry("bank", null);
        evaluator.linkage("bank", v -> noMatch());
        assertThat(
                        enforcer.prepare(
                                ctx,
                                true,
                                Map.of(),
                                values("company", "甲", "bank", "手填"),
                                Set.of("company", "bank"),
                                ALL))
                .containsEntry("bank", null);
        assertThat(enforcer.prepare(ctx, true, Map.of(), values("company", "甲"), Set.of(), ALL))
                .containsEntry("bank", null);
    }

    @Test
    void noMatchOnUpdateClearsStoredAndForgedValue() {
        evaluator.linkage("bank", v -> "丙".equals(v.get("company")) ? noMatch() : applied("银行"));
        var ctx = mainRules(Map.of("bank", linkage(true, "company")));
        // 依赖变化后未命中：库中旧值被清掉。
        assertThat(
                        enforcer.prepare(
                                ctx,
                                false,
                                values("company", "甲", "bank", "银行"),
                                values("company", "丙"),
                                Set.of("company"),
                                ALL))
                .containsEntry("bank", null);
        // 依赖不变、只提交伪造值：同样重算，未命中即写空。
        assertThat(
                        enforcer.prepare(
                                ctx,
                                false,
                                values("company", "丙", "bank", null),
                                values("bank", "伪造"),
                                Set.of("bank"),
                                ALL))
                .containsEntry("bank", null);
    }

    /** 2026-09-29：readOnly 为 null 按只读（原按可编辑、保存不重算）。 */
    @Test
    void nullReadOnlyLinkageIsForcedAsReadOnly() {
        bankFromCompany();
        var ctx = mainRules(Map.of("bank", linkage(null, "company")));
        var result =
                enforcer.prepare(
                        ctx,
                        false,
                        values("company", "甲", "bank", "银行-甲"),
                        values("company", "乙", "bank", "用户改过"),
                        Set.of("company", "bank"),
                        ALL);
        assertThat(result).containsEntry("bank", "银行-乙");
        evaluator.linkage("bank", v -> noMatch());
        assertThat(
                        enforcer.prepare(
                                ctx,
                                true,
                                Map.of(),
                                values("company", "丙", "bank", "手填"),
                                Set.of("company", "bank"),
                                ALL))
                .containsEntry("bank", null);
    }

    @Test
    void unauthorizedReadonlyKeepsOldValueAndDropsClientValue() {
        evaluator.linkage("bank", v -> noMatch());
        var result =
                enforcer.prepare(
                        mainRules(Map.of("bank", linkage(true, "company"))),
                        false,
                        values("company", "甲", "bank", "旧值"),
                        values("company", "丙", "bank", "伪造"),
                        Set.of("company", "bank"),
                        Set.of("company"));
        assertThat(result).doesNotContainKey("bank").containsEntry("company", "丙");
    }

    @Test
    void errorStateRejectsSaveAndNamesTheField() {
        evaluator.linkage("bank", v -> failed("TOO_MANY_ROWS", "命中超过 5000 行"));
        assertThatThrownBy(
                        () ->
                                enforcer.prepare(
                                        mainRules(Map.of("bank", linkage(true, "company"))),
                                        true,
                                        Map.of(),
                                        values("company", "甲"),
                                        Set.of("company"),
                                        ALL))
                .isInstanceOf(ServiceException.class)
                .hasMessage("字段「bank」的数据联动无法求值：命中超过 5000 行")
                .satisfies(
                        e ->
                                assertThat(FieldRuleEnforcer.fieldId((ServiceException) e))
                                        .isEqualTo("bank"));
    }

    @Test
    void nonReadonlyLinkageIsNotRecomputedOnSave() {
        evaluator.linkage("memo", v -> applied("服务端建议值"));
        var result =
                enforcer.prepare(
                        mainRules(Map.of("memo", linkage(false, "company"))),
                        true,
                        Map.of(),
                        values("company", "甲", "memo", "用户改过"),
                        Set.of("company", "memo"),
                        ALL);
        assertThat(result).containsEntry("memo", "用户改过");
        assertThat(evaluator.calls).isEmpty();
    }

    @Test
    void updateWithoutDependencyChangeDoesNotRecompute() {
        bankFromCompany();
        var result =
                enforcer.prepare(
                        mainRules(Map.of("bank", linkage(true, "company"))),
                        false,
                        values("company", "甲", "bank", "旧库值"),
                        values("name", "只改名称", "company", "甲"),
                        Set.of("name", "company"),
                        ALL);
        assertThat(result).doesNotContainKey("bank");
        assertThat(evaluator.calls).isEmpty();
    }

    @Test
    void numericReadBackTypeIsNotMistakenForChange() {
        evaluator.linkage("bank", v -> applied("按数量"));
        var result =
                enforcer.prepare(
                        mainRules(Map.of("bank", linkage(true, "qty"))),
                        false,
                        values("qty", 3),
                        values("qty", "3.0"),
                        Set.of("qty"),
                        ALL);
        assertThat(result).doesNotContainKey("bank");
    }

    @Test
    void forcedWriteRespectsFieldWritePermission() {
        bankFromCompany();
        var result =
                enforcer.prepare(
                        mainRules(Map.of("bank", linkage(true, "company"))),
                        false,
                        values("company", "甲", "bank", "旧值"),
                        values("company", "乙"),
                        Set.of("company"),
                        Set.of("company"));
        assertThat(result).doesNotContainKey("bank");
    }

    @Test
    void missingResultRejectsClientSuppliedValueButSkipsUntouchedField() {
        var ctx = mainRules(Map.of("bank", linkage(true, "company")));
        assertThatThrownBy(
                        () ->
                                enforcer.prepare(
                                        ctx,
                                        false,
                                        values("company", "甲"),
                                        values("bank", "伪造"),
                                        Set.of("bank"),
                                        ALL))
                .hasMessageContaining("无法求值");
        assertThat(
                        enforcer.prepare(
                                ctx,
                                false,
                                values("company", "甲"),
                                values("company", "乙"),
                                Set.of("company"),
                                ALL))
                .doesNotContainKey("bank");
    }

    @Test
    void chainedReadonlyLinkagesSeeUpstreamForcedValue() {
        bankFromCompany();
        evaluator.linkage("branch", v -> applied(v.get("bank") + "-本店"));
        var result =
                enforcer.prepare(
                        mainRules(
                                Map.of(
                                        "branch", linkage(true, "bank"),
                                        "bank", linkage(true, "company"))),
                        false,
                        values("company", "甲", "bank", "银行-甲", "branch", "银行-甲-本店"),
                        values("company", "乙"),
                        Set.of("company"),
                        ALL);
        assertThat(result).containsEntry("bank", "银行-乙").containsEntry("branch", "银行-乙-本店");
    }

    @Test
    void referenceOutOfFilterRejected() {
        evaluator.reference("account", v -> within("a1"));
        assertThatThrownBy(
                        () ->
                                enforcer.validateReferences(
                                        mainRules(Map.of("account", filter("company"))),
                                        values("company", "甲", "account", "a2"),
                                        Map.of(),
                                        true))
                .hasMessage("「account」所选记录不符合对象引用筛选，请重新选择");
        enforcer.validateReferences(
                mainRules(Map.of("account", filter("company"))),
                values("company", "甲", "account", "a1"),
                Map.of(),
                true);
    }

    @Test
    void referenceWithMissingDependencyRejected() {
        evaluator.reference("account", v -> scopePending("company"));
        assertThatThrownBy(
                        () ->
                                enforcer.validateReferences(
                                        mainRules(Map.of("account", filter("company"))),
                                        values("account", "a1"),
                                        Map.of(),
                                        true))
                .hasMessage("请先填写「company」再选择「account」");
    }

    @Test
    void referenceErrorStateRejected() {
        evaluator.reference("account", v -> scopeFailed("SOURCE_NOT_READABLE", "没有来源读取权限"));
        assertThatThrownBy(
                        () ->
                                enforcer.validateReferences(
                                        mainRules(Map.of("account", filter("company"))),
                                        values("company", "甲", "account", "a1"),
                                        Map.of(),
                                        true))
                .hasMessage("「account」的引用筛选无法求值：没有来源读取权限");
    }

    @Test
    void legacyOutOfRangeKeptIfUnchanged() {
        evaluator.reference("account", v -> within("a1"));
        var ctx = mainRules(Map.of("account", filter("company")));
        var stored = values("company", "甲", "account", 7L);
        enforcer.validateReferences(
                ctx, values("company", "甲", "account", "7", "name", "改名"), stored, false);
        assertThat(evaluator.calls).isEmpty();
        // 依赖变化即重新复核，旧值不再豁免。
        assertThatThrownBy(
                        () ->
                                enforcer.validateReferences(
                                        ctx, values("company", "乙", "account", "7"), stored, false))
                .hasMessageContaining("不符合对象引用筛选");
    }

    @Test
    void referenceCheckUsesServerMergedValues() {
        evaluator.reference("account", v -> "甲".equals(v.get("company")) ? within("a1") : within());
        enforcer.validateReferences(
                mainRules(Map.of("account", filter("company"))),
                values("company", "甲", "account", "a1"),
                values("company", "甲", "account", "a0"),
                false);
        assertThat(evaluator.calls("REFERENCE", "account").getFirst().values())
                .containsEntry("company", "甲");
    }

    @Test
    void newRowReadonlyUsesMasterCandidateValues() {
        evaluator.linkage("rate", v -> applied("税率-" + v.get("company")));
        var result =
                enforcer.prepareRow(
                        detailRules(Map.of("rate", linkage(true, "company"))),
                        definition(Map.of(), Map.of("rate", linkage(true, "company")))
                                .details()
                                .getFirst(),
                        "k1",
                        null,
                        true,
                        Map.of(),
                        values("rate", "伪造"),
                        values("company", "甲"),
                        values("company", "乙"),
                        Set.of("rate"));
        assertThat(result).containsEntry("rate", "税率-乙");
        var call = evaluator.calls("LINKAGE", "rate").getFirst();
        assertThat(call.detailId()).isEqualTo(DETAIL);
        assertThat(call.rowKey()).isEqualTo("k1");
    }

    @Test
    void unchangedRowIsNotRecomputedAndMasterChangeRecomputes() {
        evaluator.linkage("rate", v -> applied("税率-" + v.get("company")));
        var rules = Map.of("rate", linkage(true, "company"));
        var ctx = detailRules(rules);
        var detail = ctx.definition().details().getFirst();
        var unchanged =
                enforcer.prepareRow(
                        ctx,
                        detail,
                        "row-1",
                        "1",
                        false,
                        values("rate", "税率-甲"),
                        values(),
                        values("company", "甲"),
                        values("company", "甲"),
                        Set.of());
        assertThat(unchanged).doesNotContainKey("rate");
        assertThat(evaluator.calls).isEmpty();
        var changed =
                enforcer.prepareRow(
                        ctx,
                        detail,
                        "row-1",
                        "1",
                        false,
                        values("rate", "税率-甲"),
                        values(),
                        values("company", "甲"),
                        values("company", "乙"),
                        Set.of());
        assertThat(changed).containsEntry("rate", "税率-乙");
    }

    @Test
    void groupRowsAreEvaluatedInOneCall() {
        evaluator.linkage("rate", v -> applied("税率-" + v.get("company") + "-" + v.get("category")));
        var ctx = detailRules(Map.of("rate", linkage(true, "company")));
        var detail = ctx.definition().details().getFirst();
        List<FieldRuleEnforcer.RowState> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++)
            rows.add(
                    new FieldRuleEnforcer.RowState(
                            "k" + i,
                            i < 2 ? null : Integer.toString(i),
                            i < 2,
                            i < 2 ? Map.of() : values("category", "C" + i, "rate", "旧"),
                            i < 2 ? values("category", "C" + i) : values(),
                            Set.of()));
        var outcomes =
                enforcer.prepareRows(
                        ctx, detail, rows, values("company", "甲"), values("company", "乙"));
        assertThat(evaluator.rowBatches.get()).isEqualTo(1);
        assertThat(outcomes)
                .extracting(o -> o.input().get("rate"))
                .containsExactly("税率-乙-C0", "税率-乙-C1", "税率-乙-C2", "税率-乙-C3", "税率-乙-C4");
        // 主表与本行依赖都没变的已有行不进入求值批次。
        evaluator.rowBatches.set(0);
        var untouched =
                enforcer.prepareRows(
                        ctx,
                        detail,
                        rows.subList(2, 5),
                        values("company", "甲"),
                        values("company", "甲"));
        assertThat(evaluator.rowBatches.get()).isZero();
        assertThat(untouched).allSatisfy(o -> assertThat(o.input()).doesNotContainKey("rate"));
    }

    @Test
    void oneFailingRowDoesNotHideOtherRowsResults() {
        evaluator.linkage(
                "rate",
                v ->
                        "坏".equals(v.get("category"))
                                ? failed("MULTI_ROW_ERROR", "命中 2 行")
                                : applied("好"));
        var ctx = detailRules(Map.of("rate", linkage(true, "category")));
        var outcomes =
                enforcer.prepareRows(
                        ctx,
                        ctx.definition().details().getFirst(),
                        List.of(
                                new FieldRuleEnforcer.RowState(
                                        "k1",
                                        null,
                                        true,
                                        Map.of(),
                                        values("category", "好"),
                                        Set.of()),
                                new FieldRuleEnforcer.RowState(
                                        "k2",
                                        null,
                                        true,
                                        Map.of(),
                                        values("category", "坏"),
                                        Set.of())),
                        Map.of(),
                        Map.of());
        assertThat(evaluator.rowBatches.get()).isEqualTo(1);
        assertThat(outcomes.get(0).error()).isNull();
        assertThat(outcomes.get(0).input()).containsEntry("rate", "好");
        assertThat(FieldRuleEnforcer.fieldId(outcomes.get(1).error())).isEqualTo("rate");
    }

    @Test
    void rowErrorCarriesFieldIdForCellLocation() {
        evaluator.linkage("rate", v -> failed("MULTI_ROW_ERROR", "命中 2 行"));
        var ctx = detailRules(Map.of("rate", linkage(true, "category")));
        var detail = ctx.definition().details().getFirst();
        assertThatThrownBy(
                        () ->
                                enforcer.prepareRow(
                                        ctx,
                                        detail,
                                        "k1",
                                        null,
                                        true,
                                        Map.of(),
                                        values("category", "A"),
                                        Map.of(),
                                        Map.of(),
                                        Set.of("category")))
                .satisfies(
                        e ->
                                assertThat(FieldRuleEnforcer.fieldId((ServiceException) e))
                                        .isEqualTo("rate"));
    }

    @Test
    void rowReferenceChecksRowAndMasterDependencies() {
        evaluator.reference(
                "subject", v -> "A".equals(v.get("category")) ? within("s1") : within("s2"));
        var ctx = detailRules(Map.of("subject", filter("category")));
        var detail = ctx.definition().details().getFirst();
        enforcer.validateRowReferences(
                ctx,
                detail,
                values("category", "A", "subject", "s1"),
                Map.of(),
                Map.of(),
                Map.of(),
                true);
        assertThatThrownBy(
                        () ->
                                enforcer.validateRowReferences(
                                        ctx,
                                        detail,
                                        values("category", "B", "subject", "s1"),
                                        values("category", "A", "subject", "s1"),
                                        Map.of(),
                                        Map.of(),
                                        false))
                .hasMessageContaining("不符合对象引用筛选")
                .satisfies(
                        e ->
                                assertThat(FieldRuleEnforcer.fieldId((ServiceException) e))
                                        .isEqualTo("subject"));
        evaluator.calls.clear();
        enforcer.validateRowReferences(
                ctx,
                detail,
                values("category", "B", "subject", "s1"),
                values("category", "B", "subject", "s1"),
                Map.of(),
                Map.of(),
                false);
        assertThat(evaluator.calls).isEmpty();
    }

    @Test
    void newRowNoMatchClearsCopiedValue() {
        evaluator.linkage("rate", v -> noMatch());
        var ctx = detailRules(Map.of("rate", linkage(true, "company")));
        var result =
                enforcer.prepareRow(
                        ctx,
                        ctx.definition().details().getFirst(),
                        "copy",
                        null,
                        true,
                        Map.of(),
                        values("rate", "复制来的值"),
                        values(),
                        values("company", "丙"),
                        Set.of("rate"));
        assertThat(result).containsEntry("rate", null);
    }

    @Test
    void detailsAffectedByMasterOnlyForReadonlyOrFilteredRules() {
        var readonly = definition(Map.of(), Map.of("rate", linkage(true, "company")));
        assertThat(
                        enforcer.detailsAffectedByMaster(
                                readonly, values("company", "甲"), values("company", "乙")))
                .containsExactly(DETAIL);
        assertThat(
                        enforcer.detailsAffectedByMaster(
                                readonly, values("company", "甲"), values("company", "甲")))
                .isEmpty();
        var editable = definition(Map.of(), Map.of("rate", linkage(false, "company")));
        assertThat(
                        enforcer.detailsAffectedByMaster(
                                editable, values("company", "甲"), values("company", "乙")))
                .isEmpty();
        var filtered = definition(Map.of(), Map.of("subject", filter("company")));
        assertThat(
                        enforcer.detailsAffectedByMaster(
                                filtered, values("company", "甲"), values("company", "乙")))
                .containsExactly(DETAIL);
        // 2026-09-29：readOnly 为 null 的联动与公式默认值同样随主表依赖补齐整组重算。
        var byDefault = definition(Map.of(), Map.of("rate", linkage(null, "company")));
        assertThat(
                        enforcer.detailsAffectedByMaster(
                                byDefault, values("company", "甲"), values("company", "乙")))
                .containsExactly(DETAIL);
        var computed = definition(Map.of(), Map.of("amount", formula("line_qty * qty", null)));
        assertThat(enforcer.detailsAffectedByMaster(computed, values("qty", 1), values("qty", 2)))
                .containsExactly(DETAIL);
    }
}
