package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.richuang.os.common.dto.DynamicConditionDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.runtime.service.rules.FieldRuleConditions;

import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * 对象规则条件里的相对日期（不查库）：引用筛选原样放行（交给 RecordConditions.appendRule 按当天换算），数据联动一律失败（取到的值写进字段，过了零点不会自己变）。
 */
class RelativeDateRuleConditionsTest {
    private static FieldDefinition field(String id, String type) {
        return new FieldDefinition(id, id, id, "来源" + id, type, null, null, null, false, false, 0);
    }

    private static final DataCenter.Definition SOURCE =
            new DataCenter.Definition(
                    "900",
                    "room",
                    "房间预订",
                    null,
                    "public",
                    "biz_room",
                    "GENERATED",
                    false,
                    "s_name",
                    DataCenter.Settings.defaults(),
                    List.of(
                            field("s_name", "TEXT"),
                            field("s_checkout", "DATE"),
                            field("s_stock", "INTEGER")),
                    Map.of(),
                    List.of(),
                    List.of(),
                    List.of());

    private static final Set<String> ALLOWED = Set.of("s_name", "s_checkout", "s_stock");

    private static FieldRules.Condition constant(String field, String op, Object value) {
        return new FieldRules.Condition(field, op, "CONSTANT", value, null);
    }

    @Test
    void referenceFilterPassesTheRelativeValueThrough() {
        var value = Map.of("relative", "THIS_WEEK");
        var scope =
                FieldRuleConditions.compile(
                        List.of(constant("s_checkout", "between", value)),
                        SOURCE,
                        ALLOWED,
                        Map.of(),
                        Map.of());
        assertThat(scope.state()).isEqualTo("APPLIED");
        DynamicConditionDTO.Item item = scope.conditions().getItems().getFirst();
        assertThat(item.getOperator()).isEqualTo("between");
        assertThat(item.getValue()).isEqualTo(value);
    }

    @Test
    void referenceFilterRejectsBadRelativeValues() {
        var wrongField =
                FieldRuleConditions.compile(
                        List.of(constant("s_stock", "gt", Map.of("relative", "TODAY"))),
                        SOURCE,
                        ALLOWED,
                        Map.of(),
                        Map.of());
        assertThat(wrongField.state()).isEqualTo("CONDITION_UNSUPPORTED");
        assertThat(wrongField.conditions()).isNull();
        var wrongCode =
                FieldRuleConditions.compile(
                        List.of(constant("s_checkout", "lt", Map.of("relative", "SOMEDAY"))),
                        SOURCE,
                        ALLOWED,
                        Map.of(),
                        Map.of());
        assertThat(wrongCode.state()).isEqualTo("CONDITION_UNSUPPORTED");
        assertThat(wrongCode.message()).contains("SOMEDAY");
    }

    @Test
    void linkageNeverAcceptsRelativeDates() {
        for (boolean auto : new boolean[] {false, true}) {
            var scope =
                    FieldRuleConditions.compile(
                            List.of(constant("s_checkout", "lt", Map.of("relative", "TODAY"))),
                            SOURCE,
                            ALLOWED,
                            Map.of(),
                            Map.of(),
                            auto,
                            "1");
            assertThat(scope.state()).as("autoUpdate=" + auto).isEqualTo("CONDITION_UNSUPPORTED");
            assertThat(scope.message()).contains("数据联动不支持相对日期");
            assertThat(scope.conditions()).isNull();
        }
    }

    @Test
    void concreteDateConditionsAreUnchanged() {
        var scope =
                FieldRuleConditions.compile(
                        List.of(
                                constant(
                                        "s_checkout",
                                        "between",
                                        List.of("2026-10-01", "2026-10-31")),
                                constant("s_checkout", "lt", "2026-10-03")),
                        SOURCE,
                        ALLOWED,
                        Map.of(),
                        Map.of(),
                        false,
                        null);
        assertThat(scope.state()).isEqualTo("APPLIED");
        assertThat(scope.conditions().getItems())
                .extracting(
                        DynamicConditionDTO.Item::getOperator, DynamicConditionDTO.Item::getValue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "between", List.of("2026-10-01", "2026-10-31")),
                        org.assertj.core.groups.Tuple.tuple("lt", "2026-10-03"));
    }
}
