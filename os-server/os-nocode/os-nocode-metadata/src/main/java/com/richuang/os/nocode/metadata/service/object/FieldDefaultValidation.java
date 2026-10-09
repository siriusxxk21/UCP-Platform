package com.richuang.os.nocode.metadata.service.object;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.DataCenter.FieldOptions;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.api.FieldRuleMatrix;
import com.richuang.os.nocode.api.HyperlinkValue;
import com.richuang.os.nocode.enums.FieldTypeEnum;
import com.richuang.os.nocode.enums.SelectionSourceEnum;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** 常量默认值在设计保存时校验，避免把无效配置推迟到发布 DDL 或业务填写。 */
final class FieldDefaultValidation {
    private FieldDefaultValidation() {}

    static void validate(FieldDefinition field, FieldOptions options, ObjectMapper json) {
        String raw = options.defaultValue();
        if (raw == null) return;
        var type = FieldTypeEnum.fromCode(field.type());
        // 选项类未作答与“选中默认项”无法区分，会污染统计；数据库列上的 DEFAULT 随发布计划删除。
        if (FieldRuleMatrix.OPTION_TYPES.contains(type))
            throw invalid("「" + field.name() + "」是选项类字段，不能设置默认值（未作答与选中无法区分，影响统计）");
        try {
            if (raw.length() > 4000) throw new IllegalArgumentException();
            switch (type) {
                case INTEGER, ORGANIZATION, USER, DEPARTMENT, POST, USER_GROUP, REFERENCE ->
                        new BigInteger(raw).longValueExact();
                case DECIMAL, MONEY, PERCENT -> requireDecimal(field, raw);
                case DATE -> LocalDate.parse(raw);
                case DATETIME -> {
                    if (options.nativeType() != null
                            && options.nativeType().contains("with time zone"))
                        OffsetDateTime.parse(raw);
                    else LocalDateTime.parse(raw.replace(' ', 'T'));
                }
                case TIME -> LocalTime.parse(raw);
                case BOOLEAN -> {
                    if (!Set.of("true", "false").contains(raw))
                        throw new IllegalArgumentException();
                }
                case UUID -> {
                    if (!raw.matches(
                            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                        throw new IllegalArgumentException();
                    UUID.fromString(raw);
                }
                case URL -> hyperlink(raw, json);
                case MULTI_SELECT, REGION, CASCADE, IMAGE, ATTACHMENT -> {
                    Object decoded = json.readValue(raw, Object.class);
                    if (!(decoded instanceof List<?> items)
                            || items.size() > 100
                            || items.stream().anyMatch(item -> !(item instanceof String)))
                        throw new IllegalArgumentException();
                    if ((type == FieldTypeEnum.MULTI_SELECT
                                    || type == FieldTypeEnum.REGION
                                    || type == FieldTypeEnum.CASCADE)
                            && (options.selection() == null
                                    || SelectionSourceEnum.LOCAL_OPTIONS.matches(
                                            options.selection().kind())))
                        for (Object item : items)
                            if (options.options().stream()
                                    .noneMatch(
                                            candidate ->
                                                    candidate.code().equals(item)
                                                            && !Boolean.TRUE.equals(
                                                                    candidate.disabled())))
                                throw new IllegalArgumentException();
                    if ((type == FieldTypeEnum.IMAGE || type == FieldTypeEnum.ATTACHMENT)
                            && items.stream()
                                    .anyMatch(item -> !item.toString().matches("[1-9][0-9]*")))
                        throw new IllegalArgumentException();
                }
                case SELECT -> {
                    if ((options.selection() == null
                                    || SelectionSourceEnum.LOCAL_OPTIONS.matches(
                                            options.selection().kind()))
                            && options.options().stream()
                                    .noneMatch(
                                            candidate ->
                                                    candidate.code().equals(raw)
                                                            && !Boolean.TRUE.equals(
                                                                    candidate.disabled())))
                        throw new IllegalArgumentException();
                }
                case AUTO_NUMBER, FORMULA, SUMMARY -> throw new IllegalArgumentException();
                default -> {
                    if (field.length() != null && raw.length() > field.length())
                        throw new IllegalArgumentException();
                    if (options.pattern() != null
                            && !options.pattern().isBlank()
                            && !Pattern.matches(options.pattern(), raw))
                        throw new IllegalArgumentException();
                }
            }
            if (type.isNumeric()) {
                BigDecimal value = new BigDecimal(raw);
                if (options.minimum() != null
                                && value.compareTo(new BigDecimal(options.minimum())) < 0
                        || options.maximum() != null
                                && value.compareTo(new BigDecimal(options.maximum())) > 0)
                    throw new IllegalArgumentException();
            }
        } catch (Exception ex) {
            throw invalid("字段“" + field.name() + "”的默认值格式、范围或候选项无效，请重新配置");
        }
    }

    /**
     * 十进制字面量须能按字段的位数与小数位无损表示（不取整、不截断）；不符合时抛 IllegalArgumentException 或
     * ArithmeticException。常量默认值与数据联动「没有匹配记录时填入」共用这一段判断。
     */
    static void requireDecimal(FieldDefinition field, String raw) {
        BigDecimal value = new BigDecimal(raw);
        // 先界定可表示位数，避免极端指数在 setScale 时扩展为巨型整数。
        BigDecimal compact = value.stripTrailingZeros();
        int precision = field.precision() == null ? 38 : field.precision();
        int scale = field.scale() == null ? Math.max(0, compact.scale()) : field.scale();
        if (precision < 1
                || precision > 38
                || scale < 0
                || scale > precision
                || compact.scale() < -38
                || compact.scale() > 38
                || compact.signum() != 0
                        && (long) compact.precision() - compact.scale() > precision - scale)
            throw new IllegalArgumentException();
        if (field.scale() != null) value = value.setScale(field.scale(), RoundingMode.UNNECESSARY);
        if (field.precision() != null && value.precision() > field.precision())
            throw new IllegalArgumentException();
    }

    /** 草稿持久化时升级旧链接常量；发布快照和纳管表的原有默认值不在这里改写。 */
    static String normalizeDefault(FieldDefinition field, FieldOptions options, ObjectMapper json) {
        String raw = options.defaultValue();
        if (raw == null || !FieldTypeEnum.URL.matches(field.type())) return raw;
        String normalized;
        try {
            Map<String, String> value = hyperlink(raw, json);
            normalized = value == null ? null : json.writeValueAsString(value);
        } catch (Exception ex) {
            throw invalid("字段“" + field.name() + "”的默认链接无效，请重新配置");
        }
        if (normalized != null && normalized.length() > 4000)
            throw invalid("字段“" + field.name() + "”的默认链接过长，请缩短链接地址或显示文字");
        return normalized;
    }

    private static Map<String, String> hyperlink(String raw, ObjectMapper json) {
        Object value;
        try {
            value = json.readValue(raw, Object.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException legacyText) {
            // 旧草稿使用纯地址；解析后保存为 jsonb 列需要的链接对象。
            value = raw;
        }
        return HyperlinkValue.normalize(value);
    }
}
