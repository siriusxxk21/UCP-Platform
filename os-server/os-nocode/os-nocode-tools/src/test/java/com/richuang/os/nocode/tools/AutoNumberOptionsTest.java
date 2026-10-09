package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

class AutoNumberOptionsTest {
    private final FieldDefinition field =
            new FieldDefinition(
                    "n", "1", "number", "编号", "AUTO_NUMBER", null, null, null, false, false, 0);

    @Test
    void datePrecisionProtectsResetUniquenessAndWidthDoesNotTruncate() {
        assertThatThrownBy(
                        () ->
                                AutoNumberOptions.validate(
                                        field, new AutoNumberOptions("", "yyyy", 3, 1L, "MONTH")))
                .hasMessageContaining("日期精度");
        assertThatThrownBy(
                        () ->
                                AutoNumberOptions.validate(
                                        field, new AutoNumberOptions("", "", 3, 1L, "DAY")))
                .hasMessageContaining("日期精度");
        assertThatThrownBy(
                        () ->
                                AutoNumberOptions.validate(
                                        field, new AutoNumberOptions("", "yyyyMMdd", 0, 1L, "DAY")))
                .hasMessageContaining("流水位数");
        assertThatThrownBy(
                        () ->
                                AutoNumberOptions.validate(
                                        field, new AutoNumberOptions("", "yyyyMMdd", 3, 0L, "DAY")))
                .hasMessageContaining("起始值");
        var rule = new AutoNumberOptions("N-", "yyyyMMdd", 3, 1L, "MONTH");
        AutoNumberOptions.validate(field, rule);
        var today = LocalDate.of(2026, 9, 14);
        assertThat(rule.format(today, 1)).isEqualTo("N-20260914001");
        assertThat(rule.format(today, 1000)).isEqualTo("N-202609141000");
        assertThat(rule.periodKey(today)).isEqualTo(rule.periodKey(today.plusDays(1)));
        assertThat(rule.periodKey(today)).isNotEqualTo(rule.periodKey(today.plusMonths(1)));
        for (String cycle : java.util.List.of("NONE", "YEAR", "MONTH", "DAY"))
            assertThat(new AutoNumberOptions("", "yyyyMMdd", 1, 1L, cycle).periodKey(today))
                    .hasSizeLessThanOrEqualTo(16);
        assertThat(new AutoNumberOptions("", "", 1, 1L, "NONE").periodKey(today))
                .isEqualTo(
                        new AutoNumberOptions("", "", 1, 1L, "NONE").periodKey(today.plusYears(1)));
    }
}
