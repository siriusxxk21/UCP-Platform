package com.richuang.os.nocode.api;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.enums.FieldTypeEnum;
import com.richuang.os.nocode.enums.NumberPeriodEnum;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/** 对象级自动编号规则；流水位数是最少补零宽度，超宽保留完整流水。 */
public record AutoNumberOptions(
        String prefix,
        String dateFormat,
        Integer sequenceLength,
        Long startValue,
        String resetCycle) {
    public static void validate(FieldDefinition field, AutoNumberOptions rule) {
        if (rule == null) return;
        if (!FieldTypeEnum.AUTO_NUMBER.matches(field.type())) throw invalid("只有自动编号字段可配置编号规则");
        if (rule.prefix == null
                || rule.prefix.length() > 40
                || rule.prefix.codePoints().anyMatch(Character::isISOControl))
            throw invalid("编号前缀最多 40 字符且不能包含控制字符");
        if (rule.dateFormat == null
                || !Set.of("", "yyyy", "yyyyMM", "yyyyMMdd").contains(rule.dateFormat))
            throw invalid("编号日期格式无效");
        if (rule.sequenceLength == null || rule.sequenceLength < 1 || rule.sequenceLength > 12)
            throw invalid("编号流水位数必须为 1 至 12");
        if (rule.startValue == null || rule.startValue < 1 || rule.startValue > 999999999999L)
            throw invalid("编号起始值必须为 1 至 999999999999");
        NumberPeriodEnum period;
        try {
            period = NumberPeriodEnum.fromCode(rule.resetCycle);
        } catch (RuntimeException e) {
            throw invalid("编号重置周期无效");
        }
        int required =
                switch (period) {
                    case NONE -> 0;
                    case YEAR -> 4;
                    case MONTH -> 6;
                    case DAY -> 8;
                };
        if (rule.dateFormat.length() < required) throw invalid("编号日期精度必须覆盖重置周期，避免周期重置后重复");
    }

    public String periodKey(LocalDate date) {
        var period = NumberPeriodEnum.fromCode(resetCycle);
        String pattern =
                switch (period) {
                    case NONE -> "";
                    case YEAR -> "yyyy";
                    case MONTH -> "yyyyMM";
                    case DAY -> "yyyyMMdd";
                };
        return resetCycle
                + ":"
                + (pattern.isEmpty() ? "" : date.format(DateTimeFormatter.ofPattern(pattern)));
    }

    public String format(LocalDate date, long next) {
        return prefix
                + (dateFormat.isEmpty() ? "" : date.format(DateTimeFormatter.ofPattern(dateFormat)))
                + String.format(Locale.ROOT, "%0" + sequenceLength + "d", next);
    }
}
