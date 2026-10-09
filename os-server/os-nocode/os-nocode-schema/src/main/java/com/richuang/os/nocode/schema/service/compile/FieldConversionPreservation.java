package com.richuang.os.nocode.schema.service.compile;

import com.richuang.os.nocode.api.DataCenter.FieldOptions;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.api.SelectionFields;
import com.richuang.os.nocode.enums.FieldTypeEnum;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 首版可完整保留值的规则白名单。发布时与编辑预检均由同一计划器全列检查。 */
final class FieldConversionPreservation {
    private static final Pattern NUMERIC = Pattern.compile("numeric\\(([0-9]+),([0-9]+)\\)");
    private static final Pattern VARCHAR = Pattern.compile("varchar\\(([0-9]+)\\)");
    private static final Set<FieldTypeEnum> NUMERIC_TYPES =
            Set.of(FieldTypeEnum.INTEGER, FieldTypeEnum.DECIMAL, FieldTypeEnum.MONEY);
    private static final Set<FieldTypeEnum> TEXT_TYPES =
            Set.of(FieldTypeEnum.TEXT, FieldTypeEnum.TEXTAREA);

    private FieldConversionPreservation() {}

    record Rule(
            String code,
            String description,
            boolean targetInteger,
            int scale,
            int integerDigits,
            int limitLength) {}

    static Rule rule(
            FieldDefinition before,
            FieldOptions oldOptions,
            FieldDefinition target,
            FieldOptions targetOptions,
            String from,
            String to) {
        FieldTypeEnum sourceType = FieldTypeEnum.fromCode(before.type());
        FieldTypeEnum targetType = FieldTypeEnum.fromCode(target.type());
        Matcher targetNumber = NUMERIC.matcher(to);
        boolean targetInteger = "bigint".equals(to) && FieldTypeEnum.INTEGER == targetType;
        boolean targetDecimal =
                targetNumber.matches()
                        && (FieldTypeEnum.DECIMAL == targetType
                                || FieldTypeEnum.MONEY == targetType);
        if (NUMERIC_TYPES.contains(sourceType)
                && (targetInteger || targetDecimal)
                && ("bigint".equals(from) || NUMERIC.matcher(from).matches()))
            return numericRule(targetNumber, targetInteger, sourceType, targetType);
        if (TEXT_TYPES.contains(sourceType)
                && (targetInteger || targetDecimal)
                && ("text".equals(from) || VARCHAR.matcher(from).matches()))
            return textNumberRule(targetNumber, targetInteger, targetType);
        if (FieldTypeEnum.URL == sourceType
                && TEXT_TYPES.contains(targetType)
                && "jsonb".equals(from)) {
            Matcher targetText = VARCHAR.matcher(to);
            if (targetText.matches() || "text".equals(to)) {
                int limit = targetText.matches() ? Integer.parseInt(targetText.group(1)) : 0;
                return new Rule(
                        "URL_LINK_TEXT",
                        "仅当链接没有单独的显示文字时，提取完整地址作为文本；带显示文字的记录须先处理或清空本列。",
                        false,
                        0,
                        0,
                        limit);
            }
        }
        if (sameSelectionSource(before, oldOptions, target, targetOptions)) {
            if (FieldTypeEnum.SELECT == sourceType && FieldTypeEnum.MULTI_SELECT == targetType)
                return new Rule("SINGLE_TO_MULTI", "原单选稳定编码保留为只有一项的多选集合；目标选项须仍有效。", false, 0, 0, 0);
            if (FieldTypeEnum.MULTI_SELECT == sourceType && FieldTypeEnum.SELECT == targetType)
                return new Rule(
                        "MULTI_TO_SINGLE", "每条记录最多只能有一个选项；空集合归为空值，不自动取第一项。", false, 0, 0, 100);
        }
        Matcher targetText = VARCHAR.matcher(to);
        if (TEXT_TYPES.contains(sourceType)
                && TEXT_TYPES.contains(targetType)
                && (targetText.matches() || "text".equals(to))) {
            int limit = targetText.matches() ? Integer.parseInt(targetText.group(1)) : 0;
            return new Rule(
                    "TEXT_EXACT",
                    limit > 0 ? "保留原文；每个值均须不超过 " + limit + " 个字符。" : "原文完整保留。",
                    false,
                    0,
                    0,
                    limit);
        }
        return null;
    }

    private static boolean sameSelectionSource(
            FieldDefinition before,
            FieldOptions oldOptions,
            FieldDefinition target,
            FieldOptions targetOptions) {
        SelectionFields.Source source = SelectionFields.source(before, oldOptions);
        SelectionFields.Source next = SelectionFields.source(target, targetOptions);
        return source != null
                && next != null
                && source.kind().equals(next.kind())
                && java.util.Objects.equals(source.directory(), next.directory())
                && java.util.Objects.equals(source.dictionaryType(), next.dictionaryType())
                && java.util.Objects.equals(source.sourceObjectId(), next.sourceObjectId())
                && java.util.Objects.equals(source.sourceFieldId(), next.sourceFieldId());
    }

    private static Rule numericRule(
            Matcher targetNumber,
            boolean targetInteger,
            FieldTypeEnum sourceType,
            FieldTypeEnum targetType) {
        int scale = targetInteger ? 0 : Integer.parseInt(targetNumber.group(2));
        int integerDigits = targetInteger ? 19 : Integer.parseInt(targetNumber.group(1)) - scale;
        String description =
                targetInteger ? "逐值检查是否为整数且在整数范围内；不四舍五入。" : "逐值检查目标精度与小数位，精确保留数值；不截断、不四舍五入。";
        if (FieldTypeEnum.MONEY == sourceType || FieldTypeEnum.MONEY == targetType)
            description += " 金额展示方式会变化，不进行币种换算。";
        return new Rule("NUMERIC_EXACT", description, targetInteger, scale, integerDigits, 0);
    }

    private static Rule textNumberRule(
            Matcher targetNumber, boolean targetInteger, FieldTypeEnum targetType) {
        int scale = targetInteger ? 0 : Integer.parseInt(targetNumber.group(2));
        int integerDigits = targetInteger ? 19 : Integer.parseInt(targetNumber.group(1)) - scale;
        String description =
                "严格解析十进制文本并检查目标范围与精度；可去除首尾空白，允许正负号和小数点，" + "不接受空字符串、千分位、货币/百分号或科学计数法；前导零和正号不再保留。";
        if (FieldTypeEnum.MONEY == targetType) description += " 不进行币种换算。";
        return new Rule("STRICT_TEXT_DECIMAL", description, targetInteger, scale, integerDigits, 0);
    }
}
