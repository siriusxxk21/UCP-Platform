package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.enums.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

/** 规则相关编码集合被钉死：档位与来源种类只增不猜，退役档位（去重取值等）永不出现。 */
class FieldRuleEnumsTest {
    @Test
    void noRetiredModes() {
        assertThat(NocodeCodeEnum.codes(LinkageMultiRowEnum.class))
                .containsExactlyInAnyOrder("CONCAT", "FIRST", "SUM", "ERROR");
        assertThat(NocodeCodeEnum.codes(SelectionSourceEnum.class))
                .containsExactlyInAnyOrder(
                        "LOCAL_OPTIONS",
                        "SYSTEM_DICTIONARY",
                        "DIRECTORY",
                        "OBJECT_RELATION",
                        "OBJECT_FIELD_OPTIONS");
        for (String retired : Set.of("DISTINCT", "UNIQUE", "DEDUPE", "SUMMARY", "JOIN"))
            assertThatThrownBy(() -> LinkageMultiRowEnum.fromCode(retired))
                    .hasMessageContaining(retired);
    }

    @Test
    void ruleCodesArePinned() {
        assertThat(NocodeCodeEnum.codes(FieldRuleKindEnum.class))
                .containsExactlyInAnyOrder("LINKAGE", "DEFAULT_FORMULA", "REFERENCE");
        assertThat(NocodeCodeEnum.codes(RuleValueSourceEnum.class))
                // CURRENT_RECORD（2026-10-01）：只用于开启「来源变化时自动更新」的数据联动。
                .containsExactlyInAnyOrder("CONSTANT", "FORM_FIELD", "CURRENT_RECORD");
        assertThat(NocodeCodeEnum.codes(MoneyRoundingEnum.class))
                .containsExactlyInAnyOrder("HALF_UP", "FLOOR", "DOWN");
        assertThat(DependencyKindEnum.containsCode("OBJECT_RULE")).isTrue();
        assertThat(NocodeCodeEnum.codes(FieldRuleStateEnum.class))
                .containsExactlyInAnyOrder(
                        "APPLIED",
                        "INCOMPLETE_CONFIG",
                        "SOURCE_TABLE_MISSING",
                        "SOURCE_NOT_READABLE",
                        "SOURCE_FIELD_MISSING",
                        "CONDITION_UNPARSEABLE",
                        "CONDITION_MULTI_GROUP",
                        "CONDITION_TOO_DEEP",
                        "CONDITION_FIELD_MISSING",
                        "CONDITION_FIELD_NOT_FILTERABLE",
                        "CONDITION_UNSUPPORTED",
                        "PENDING_ROW_VALUE",
                        "CURRENT_FIELD_MULTI_VALUE",
                        "NO_MATCH",
                        "TOO_MANY_ROWS",
                        "MULTI_ROW_ERROR",
                        "MULTI_ROW_MODE_REJECTED",
                        "MULTI_ROW_MODE_UNKNOWN",
                        "SUM_NOT_NUMERIC",
                        "UNSUPPORTED_VALUE_KIND",
                        "OPTION_SOURCE_MODE_UNKNOWN",
                        "SOURCE_FIELD_HAS_NO_OPTIONS",
                        "VALUE_TYPE_MISMATCH",
                        "NOT_APPLICABLE",
                        "ROUNDING_MODE_UNKNOWN");
    }

    @Test
    void roundingModesMatchBusinessTable() {
        // 业务方答复的三档：1.5 → 2 / 1 / 1；-1.5 → -2 / -2 / -1；缺省为向下取整。
        var positive = new BigDecimal("1.5");
        var negative = new BigDecimal("-1.5");
        assertThat(positive.setScale(0, MoneyRoundingEnum.HALF_UP.mode()))
                .isEqualByComparingTo("2");
        assertThat(positive.setScale(0, MoneyRoundingEnum.FLOOR.mode())).isEqualByComparingTo("1");
        assertThat(positive.setScale(0, MoneyRoundingEnum.DOWN.mode())).isEqualByComparingTo("1");
        assertThat(negative.setScale(0, MoneyRoundingEnum.HALF_UP.mode()))
                .isEqualByComparingTo("-2");
        assertThat(negative.setScale(0, MoneyRoundingEnum.FLOOR.mode())).isEqualByComparingTo("-2");
        assertThat(negative.setScale(0, MoneyRoundingEnum.DOWN.mode())).isEqualByComparingTo("-1");
        assertThat(MoneyRoundingEnum.of(null)).isEqualTo(MoneyRoundingEnum.FLOOR);
        assertThatThrownBy(() -> MoneyRoundingEnum.of("CEIL")).hasMessageContaining("CEIL");
    }
}
