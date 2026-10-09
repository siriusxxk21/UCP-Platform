package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.richuang.os.common.dto.DynamicConditionDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.RecordQueryOperatorEnum;
import com.richuang.os.nocode.runtime.service.rules.FieldRuleConditions;
import com.richuang.os.nocode.runtime.service.rules.ReferenceScope;

import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * 条件替换与编译（不查库）。用例改写自老 data-linkage.service.spec.ts 的条件段与
 * virtual-table-ref-options-rowvalue.e2e.spec.ts ③c ③d ⑦；数值、日期按类型比较的数据库侧效果见
 * ReferenceRuleIntegrationTest。
 */
class FieldRuleConditionsTest {
    private static final Map<String, String> FORM =
            Map.of("f_company", "公司", "f_count", "数量", "f_day", "日期");

    private static FieldDefinition field(String id, String type) {
        return new FieldDefinition(id, id, id, "来源" + id, type, null, null, null, false, false, 0);
    }

    private static final DataCenter.Definition SOURCE =
            new DataCenter.Definition(
                    "900",
                    "account",
                    "银行口座",
                    null,
                    "public",
                    "biz_account",
                    "GENERATED",
                    false,
                    "s_name",
                    DataCenter.Settings.defaults(),
                    List.of(
                            field("s_name", "TEXT"),
                            field("s_company", "TEXT"),
                            field("s_stock", "INTEGER"),
                            field("s_opened", "DATE"),
                            field("s_tags", "MULTI_SELECT"),
                            field("s_file", "ATTACHMENT"),
                            field("s_hidden", "TEXT")),
                    Map.of(),
                    List.of(),
                    List.of(),
                    List.of());

    private static final Set<String> ALLOWED =
            Set.of("s_name", "s_company", "s_stock", "s_opened", "s_tags", "s_file");

    private static ReferenceScope compile(Map<String, Object> values, FieldRules.Condition... c) {
        return FieldRuleConditions.compile(List.of(c), SOURCE, ALLOWED, values, FORM);
    }

    private static FieldRules.Condition form(String field, String op, String formField) {
        return new FieldRules.Condition(field, op, "FORM_FIELD", null, formField);
    }

    private static FieldRules.Condition constant(String field, String op, Object value) {
        return new FieldRules.Condition(field, op, "CONSTANT", value, null);
    }

    private static DynamicConditionDTO.Item only(ReferenceScope scope) {
        assertThat(scope.state()).isEqualTo("APPLIED");
        assertThat(scope.conditions().getLogic()).isEqualTo(DynamicConditionDTO.Logic.AND);
        assertThat(scope.conditions().getItems()).hasSize(1);
        return scope.conditions().getItems().getFirst();
    }

    /** B3：本对象没有该字段也算 PENDING（点名等待的字段），不当成空串去筛。 */
    @Test
    void unknownFormFieldIsPending() {
        var scope =
                compile(
                        Map.of("ffff-unknown", "甲公司"),
                        form("s_company", "eq", "f_missing"),
                        form("s_company", "eq", "f_company"));
        assertThat(scope.state()).isEqualTo("PENDING_ROW_VALUE");
        assertThat(scope.pendingFields()).containsExactly("f_missing", "f_company");
        assertThat(scope.conditions()).isNull();
        assertThat(scope.message()).contains("「公司」");
    }

    /** B2（单元侧）：空串算没值。 */
    @Test
    void emptyStringIsPending() {
        var scope = compile(Map.of("f_company", ""), form("s_company", "eq", "f_company"));
        assertThat(scope.state()).isEqualTo("PENDING_ROW_VALUE");
        assertThat(scope.pendingFields()).containsExactly("f_company");
    }

    /** B5：多值中混入的空串被剔除，eq 改写为 in 取并集。 */
    @Test
    void dropEmptyInArray() {
        var item =
                only(
                        compile(
                                Map.of("f_company", Arrays.asList("甲公司", "", null, "甲公司", "乙公司")),
                                form("s_company", "eq", "f_company")));
        assertThat(item.getOperator()).isEqualTo("in");
        assertThat(item.getValue()).isEqualTo(List.of("甲公司", "乙公司"));
    }

    /** B5：多值全是空串仍为 PENDING。 */
    @Test
    void allEmptyArrayPending() {
        var scope =
                compile(Map.of("f_company", List.of("", "")), form("s_company", "eq", "f_company"));
        assertThat(scope.state()).isEqualTo("PENDING_ROW_VALUE");
        assertThat(scope.pendingFields()).containsExactly("f_company");
        assertThat(
                        compile(
                                        Map.of("f_company", List.of()),
                                        form("s_company", "eq", "f_company"))
                                .state())
                .isEqualTo("PENDING_ROW_VALUE");
    }

    /** B6：多值配非 eq 算子不挑其中一个。 */
    @Test
    void multiValueNonEqRejected() {
        var scope = compile(Map.of("f_count", List.of(1, 2)), form("s_stock", "gt", "f_count"));
        assertThat(scope.state()).isEqualTo("CURRENT_FIELD_MULTI_VALUE");
        assertThat(scope.conditions()).isNull();
    }

    /** B4（单元侧）：并集只在单条条件内，条件之间仍是 AND。 */
    @Test
    void unionStaysInsideOneCondition() {
        var scope =
                compile(
                        Map.of("f_company", List.of("甲公司", "乙公司")),
                        form("s_company", "eq", "f_company"),
                        constant("s_name", "like", "三"));
        assertThat(scope.conditions().getLogic()).isEqualTo(DynamicConditionDTO.Logic.AND);
        assertThat(scope.conditions().getItems())
                .extracting(DynamicConditionDTO.Item::getOperator)
                .containsExactly("in", "like");
    }

    /** B9：条件值无法按字段类型转换时整条规则失败，不跳过这一条件。 */
    @Test
    void unconvertibleValueNotSkipped() {
        var scope =
                compile(
                        Map.of("f_count", "不是数"),
                        constant("s_name", "eq", "甲-三菱"),
                        form("s_stock", "gt", "f_count"));
        assertThat(scope.state()).isEqualTo("CONDITION_UNSUPPORTED");
        assertThat(scope.conditions()).as("失败时不返回任何可执行条件").isNull();
        assertThat(
                        compile(Map.of(), constant("s_opened", "between", List.of("2026-01-01")))
                                .state())
                .isEqualTo("CONDITION_UNSUPPORTED");
        assertThat(
                        compile(Map.of("f_day", "2026-01-01"), form("s_opened", "between", "f_day"))
                                .state())
                .as("between 只能配固定值")
                .isEqualTo("CONDITION_UNSUPPORTED");
        assertThat(compile(Map.of(), constant("s_name", "startWith", "甲")).state())
                .isEqualTo("CONDITION_UNSUPPORTED");
        assertThat(compile(Map.of(), constant("s_opened", "like", "2026")).state())
                .as("算子不适用于类型")
                .isEqualTo("CONDITION_UNSUPPORTED");
    }

    /** B12（单元侧）：数值与日期条件保持可按类型转换的原值，between 为 [起, 止]。 */
    @Test
    void typedValuesAreKept() {
        var number = only(compile(Map.of("f_count", "10"), form("s_stock", "gt", "f_count")));
        assertThat(number.getValue()).isEqualTo("10");
        assertThat(
                        RecordConditionValues.value(
                                SOURCE.fields().get(2),
                                DataCenter.FieldOptions.defaults(),
                                number.getValue(),
                                RecordQueryOperatorEnum.GT))
                .isEqualTo(10L);
        var range =
                only(
                        compile(
                                Map.of(),
                                constant(
                                        "s_opened",
                                        "between",
                                        List.of("2026-03-01", "2026-06-30"))));
        assertThat(range.getValue()).isEqualTo(List.of("2026-03-01", "2026-06-30"));
    }

    /**
     * B14：like 的 % 与 _ 按字面匹配。规则层不预先转义（避免二次转义），转义由 RecordConditions 复用的 RecordConditionValues 完成。
     */
    @Test
    void likeEscapesWildcards() {
        var item = only(compile(Map.of(), constant("s_name", "like", "50%_off\\")));
        assertThat(item.getValue()).isEqualTo("50%_off\\");
        assertThat(
                        RecordConditionValues.value(
                                SOURCE.fields().getFirst(),
                                DataCenter.FieldOptions.defaults(),
                                item.getValue(),
                                RecordQueryOperatorEnum.LIKE))
                .isEqualTo("50\\%\\_off\\\\");
    }

    /** 条件字段不可筛、不可查、不存在各回独立状态，不退化成少一个条件。 */
    @Test
    void conditionFieldStates() {
        assertThat(compile(Map.of(), constant("s_file", "eq", "x")).state())
                .isEqualTo("CONDITION_FIELD_NOT_FILTERABLE");
        assertThat(compile(Map.of(), constant("s_hidden", "eq", "x")).state())
                .isEqualTo("SOURCE_NOT_READABLE");
        assertThat(compile(Map.of(), constant("s_gone", "eq", "x")).state())
                .isEqualTo("CONDITION_FIELD_MISSING");
        assertThat(
                        compile(Map.of(), constant("s_tags", "containsAny", "A"))
                                .conditions()
                                .getItems()
                                .getFirst()
                                .getValue())
                .isEqualTo(List.of("A"));
    }

    /** 2026-10-01：为空 / 不为空编译成不带值的条件，对文本、数值、日期、多选都成立，且不等任何当前字段。 */
    @Test
    void emptinessCompilesWithoutValue() {
        for (String field : List.of("s_company", "s_stock", "s_opened", "s_tags"))
            for (String operator : List.of("isNull", "notNull")) {
                var item = only(compile(Map.of(), constant(field, operator, null)));
                assertThat(item.getField()).isEqualTo(field);
                assertThat(item.getOperator()).isEqualTo(operator);
                assertThat(item.getValue()).isNull();
            }
        var mixed =
                compile(
                        Map.of("f_company", "甲公司"),
                        constant("s_stock", "isNull", null),
                        form("s_company", "neq", "f_company"));
        assertThat(mixed.state()).isEqualTo("APPLIED");
        assertThat(mixed.conditions().getItems())
                .extracting(DynamicConditionDTO.Item::getOperator)
                .containsExactly("isNull", "neq");
    }

    /** 为空 / 不为空夹带固定值或当前字段：整条规则失败，不当成普通条件执行，也不退化成 PENDING。 */
    @Test
    void emptinessWithValueFailsClosed() {
        assertThat(compile(Map.of(), constant("s_company", "isNull", "甲公司")).state())
                .isEqualTo("CONDITION_UNSUPPORTED");
        var withForm = compile(Map.of(), form("s_company", "notNull", "f_company"));
        assertThat(withForm.state()).isEqualTo("CONDITION_UNSUPPORTED");
        assertThat(withForm.pendingFields()).isEmpty();
        assertThat(withForm.conditions()).isNull();
        assertThat(
                        compile(
                                        Map.of(),
                                        new FieldRules.Condition(
                                                "s_company",
                                                "isNull",
                                                "CONSTANT",
                                                null,
                                                "f_company"))
                                .state())
                .isEqualTo("CONDITION_UNSUPPORTED");
        assertThat(compile(Map.of(), constant("s_file", "isNull", null)).state())
                .as("不可筛的字段也不能判空")
                .isEqualTo("CONDITION_FIELD_NOT_FILTERABLE");
    }

    /** 不等于：值必须有；当前字段没值仍是 PENDING（不拿空去比）；多值当前字段不挑其中一个。 */
    @Test
    void notEqualKeepsValueRules() {
        var item = only(compile(Map.of(), constant("s_company", "neq", "甲公司")));
        assertThat(item.getOperator()).isEqualTo("neq");
        assertThat(item.getValue()).isEqualTo("甲公司");
        assertThat(compile(Map.of(), constant("s_company", "neq", "")).state())
                .isEqualTo("CONDITION_UNSUPPORTED");
        assertThat(compile(Map.of(), form("s_company", "neq", "f_company")).state())
                .isEqualTo("PENDING_ROW_VALUE");
        assertThat(
                        compile(
                                        Map.of("f_company", List.of("甲公司", "乙公司")),
                                        form("s_company", "neq", "f_company"))
                                .state())
                .isEqualTo("CURRENT_FIELD_MULTI_VALUE");
    }

    /** B8（单元侧）：条件为空表示命中全部，范围为 APPLIED 且没有条件。 */
    @Test
    void emptyConditionsApplied() {
        var scope = FieldRuleConditions.compile(List.of(), SOURCE, ALLOWED, Map.of(), FORM);
        assertThat(scope.state()).isEqualTo("APPLIED");
        assertThat(scope.conditions()).isNull();
        assertThat(scope.hasRecordKey()).isFalse();
    }

    /** $record 只能用 eq；多条 $record 条件取交集。 */
    @Test
    void recordKeyConditions() {
        var scope =
                compile(
                        Map.of("f_company", List.of("1", "2")),
                        form(FieldRules.RECORD_KEY, "eq", "f_company"),
                        constant(FieldRules.RECORD_KEY, "eq", List.of("2", "3")));
        assertThat(scope.state()).isEqualTo("APPLIED");
        assertThat(scope.hasRecordKey()).isTrue();
        assertThat(scope.recordKeys()).containsExactly("2");
        assertThat(scope.conditions()).isNull();
        assertThat(compile(Map.of(), constant(FieldRules.RECORD_KEY, "neq", "1")).state())
                .isEqualTo("CONDITION_UNSUPPORTED");
        for (String operator : List.of("isNull", "notNull")) {
            var empty = compile(Map.of(), constant(FieldRules.RECORD_KEY, operator, null));
            assertThat(empty.state()).as(operator).isEqualTo("CONDITION_UNSUPPORTED");
            assertThat(empty.message()).contains("只能用「等于」");
        }
    }

    // ── 「等于当前记录」（CURRENT_RECORD）：只用于开启自动更新的数据联动 ──

    /** 来源对象带一个指向规则所在对象的单选关联字段 s_flow，以及一个普通字段。 */
    private static final DataCenter.Definition VOUCHERS =
            new DataCenter.Definition(
                    "901",
                    "voucher",
                    "会计凭证",
                    null,
                    "public",
                    "biz_voucher",
                    "GENERATED",
                    false,
                    "s_name",
                    DataCenter.Settings.defaults(),
                    List.of(field("s_name", "TEXT"), field("s_flow", "INTEGER")),
                    Map.of(),
                    List.of(
                            new DataCenter.Relation(
                                    "r1",
                                    "s_flow",
                                    "资金流水",
                                    "REFERENCE",
                                    "100",
                                    "s_flow",
                                    null,
                                    false,
                                    "RESTRICT")),
                    List.of(),
                    List.of());

    private static FieldRules.Condition currentRecord(String field) {
        return new FieldRules.Condition(field, "eq", "CURRENT_RECORD", null, null);
    }

    private static ReferenceScope linkage(
            boolean autoUpdate, String recordId, FieldRules.Condition... c) {
        return FieldRuleConditions.compile(
                List.of(c),
                VOUCHERS,
                Set.of("s_name", "s_flow"),
                Map.of(),
                FORM,
                autoUpdate,
                recordId);
    }

    /** 有记录 ID：编译成来源对象那个引用字段上的 eq <ID>。 */
    @Test
    void currentRecordCompilesToEqualsOnTheReferenceField() {
        var item = only(linkage(true, "42", currentRecord("s_flow")));
        assertThat(item.getField()).isEqualTo("s_flow");
        assertThat(item.getOperator()).isEqualTo("eq");
        assertThat(item.getValue()).isEqualTo("42");
        // 可与其它条件并存，仍只有「且」。
        var both = linkage(true, "42", currentRecord("s_flow"), constant("s_name", "eq", "甲"));
        assertThat(both.state()).isEqualTo("APPLIED");
        assertThat(both.conditions().getItems()).hasSize(2);
    }

    /** 没有记录 ID（新建未保存）：待定，不查库；待填字段为空列表。 */
    @Test
    void currentRecordWithoutIdIsPending() {
        for (String id : new String[] {null, ""}) {
            var scope = linkage(true, id, currentRecord("s_flow"));
            assertThat(scope.state()).isEqualTo("PENDING_ROW_VALUE");
            assertThat(scope.pendingFields()).isEmpty();
            assertThat(scope.conditions()).isNull();
            assertThat(scope.message()).contains("保存后才能取值");
        }
        // 同时还有当前字段没填：按当前字段的待填提示（点名等待的字段）。
        var mixed = linkage(true, null, currentRecord("s_flow"), form("s_name", "eq", "f_company"));
        assertThat(mixed.state()).isEqualTo("PENDING_ROW_VALUE");
        assertThat(mixed.pendingFields()).containsExactly("f_company");
    }

    /** 出现在引用筛选或没开自动更新的联动里：失败（fail-closed），不退化成少一个「且」而放大命中范围。 */
    @Test
    void currentRecordOutsideAutoUpdateLinkageFailsClosed() {
        var filter =
                FieldRuleConditions.compile(
                        List.of(currentRecord("s_flow")),
                        VOUCHERS,
                        Set.of("s_name", "s_flow"),
                        Map.of(),
                        FORM);
        assertThat(filter.state()).isEqualTo("CONDITION_UNSUPPORTED");
        assertThat(filter.conditions()).isNull();
        var off = linkage(false, "42", currentRecord("s_flow"));
        assertThat(off.state()).isEqualTo("CONDITION_UNSUPPORTED");
        assertThat(off.conditions()).isNull();
    }

    /** 形状不对同样失败：算子不是等于、带了比较值、左侧是「按记录匹配」或不是单选关联字段。 */
    @Test
    void malformedCurrentRecordFailsClosed() {
        for (var c :
                List.of(
                        new FieldRules.Condition("s_flow", "neq", "CURRENT_RECORD", null, null),
                        new FieldRules.Condition("s_flow", "eq", "CURRENT_RECORD", "7", null),
                        new FieldRules.Condition(
                                "s_flow", "eq", "CURRENT_RECORD", null, "f_company"),
                        new FieldRules.Condition("$record", "eq", "CURRENT_RECORD", null, null),
                        currentRecord("s_name"),
                        currentRecord("s_missing"))) {
            var scope = linkage(true, "42", c);
            assertThat(scope.state())
                    .as(c.toString())
                    .isIn("CONDITION_UNSUPPORTED", "CONDITION_FIELD_MISSING");
            assertThat(scope.conditions()).isNull();
        }
    }
}
