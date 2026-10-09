package com.lingan.ucp.nocode.schema.service.compile;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.FieldConversions;
import com.lingan.ucp.nocode.enums.FieldConversionActionEnum;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 分页展示值与发布使用相同的已确认规则；整列未通过时只展示清空结果，不猜测部分迁移。 */
public final class FieldConversionValuePreview {
    private static final Pattern DECIMAL = Pattern.compile("numeric\\([0-9]+,([0-9]+)\\)");
    private static final Pattern PRECISION = Pattern.compile("numeric\\(([0-9]+),([0-9]+)\\)");
    private static final Pattern VARCHAR = Pattern.compile("varchar\\(([0-9]+)\\)");
    private static final Pattern STRICT_DECIMAL = Pattern.compile("^[+-]?[0-9]+(\\.[0-9]+)?$");
    private static final BigDecimal MIN_INTEGER = new BigDecimal("-9223372036854775808");
    private static final BigDecimal MAX_INTEGER = new BigDecimal("9223372036854775807");

    private FieldConversionValuePreview() {}

    public record Value(String newValue, String failureReason) {}

    public static Value value(FieldConversions.Change change, String oldValue, ObjectMapper json) {
        return value(
                change.fromFieldType(),
                change.toFieldType(),
                change.toType(),
                change.action(),
                change.failedRows(),
                oldValue,
                json);
    }

    public static Value value(
            String source,
            String target,
            String toSqlType,
            String action,
            long failedRows,
            String oldValue,
            ObjectMapper json) {
        return value(
                source, target, toSqlType, action, failedRows, oldValue, null, null, null, json);
    }

    public static Value value(
            String source,
            String target,
            String toSqlType,
            String action,
            long failedRows,
            String oldValue,
            String minimum,
            String maximum,
            String pattern,
            ObjectMapper json) {
        if (oldValue == null) return new Value(null, null);
        if (FieldConversionActionEnum.CLEAR_COLUMN.matches(action)) {
            String reason =
                    failedRows > 0
                            ? invalidReason(
                                    source, target, toSqlType, oldValue, minimum, maximum, pattern,
                                    json)
                            : null;
            return new Value(
                    null,
                    reason != null
                            ? reason + "；确认后本列此值清空。"
                            : failedRows > 0
                                    ? "本行未发现格式或范围问题，但整列另有失败值；确认后本列此值仍一并清空。"
                                    : "此类型切换不保留旧值；确认后仅清空本列。");
        }
        try {
            if ("URL".equals(source) && ("TEXT".equals(target) || "TEXTAREA".equals(target))) {
                JsonNode link = json.readTree(oldValue).path("link");
                return new Value(link.asText(), null);
            }
            if ("SELECT".equals(source) && "MULTI_SELECT".equals(target))
                return new Value(json.writeValueAsString(List.of(oldValue)), null);
            if ("MULTI_SELECT".equals(source) && "SELECT".equals(target)) {
                JsonNode selected = json.readTree(oldValue);
                return new Value(selected.isEmpty() ? null : selected.get(0).asText(), null);
            }
            if (("INTEGER".equals(target) || "DECIMAL".equals(target) || "MONEY".equals(target))
                    && ("TEXT".equals(source)
                            || "TEXTAREA".equals(source)
                            || "INTEGER".equals(source)
                            || "DECIMAL".equals(source)
                            || "MONEY".equals(source))) {
                BigDecimal number = new BigDecimal(oldValue.trim());
                if ("INTEGER".equals(target))
                    return new Value(number.toBigIntegerExact().toString(), null);
                Matcher scale = DECIMAL.matcher(toSqlType);
                if (!scale.matches()) throw invalid("目标小数精度未确定，请重新预览");
                return new Value(
                        number.setScale(Integer.parseInt(scale.group(1))).toPlainString(), null);
            }
            return new Value(oldValue, null);
        } catch (ArithmeticException | JsonProcessingException exception) {
            throw invalid("历史值与预检结果不一致，请重新预览");
        }
    }

    private static String invalidReason(
            String source,
            String target,
            String toSqlType,
            String oldValue,
            String minimum,
            String maximum,
            String pattern,
            ObjectMapper json) {
        boolean textSource = "TEXT".equals(source) || "TEXTAREA".equals(source);
        boolean numberSource =
                "INTEGER".equals(source) || "DECIMAL".equals(source) || "MONEY".equals(source);
        boolean numberTarget =
                "INTEGER".equals(target) || "DECIMAL".equals(target) || "MONEY".equals(target);
        try {
            if (numberTarget && (textSource || numberSource)) {
                String candidate = textSource ? oldValue.trim() : oldValue;
                if (textSource
                        && (candidate.length() > 1000
                                || !STRICT_DECIMAL.matcher(candidate).matches()))
                    return "不是可严格解析的十进制数";
                BigDecimal number = new BigDecimal(candidate);
                if ("INTEGER".equals(target)) {
                    if (number.stripTrailingZeros().scale() > 0) return "含有目标整数无法保留的小数位";
                    if (number.compareTo(MIN_INTEGER) < 0 || number.compareTo(MAX_INTEGER) > 0)
                        return "超出整数范围";
                } else {
                    Matcher precision = PRECISION.matcher(toSqlType);
                    if (!precision.matches()) return "目标小数精度无效";
                    int digits =
                            Integer.parseInt(precision.group(1))
                                    - Integer.parseInt(precision.group(2));
                    int scale = Integer.parseInt(precision.group(2));
                    if (number.scale() > scale && number.stripTrailingZeros().scale() > scale)
                        return "小数位超过目标精度，不能无损保留";
                    if (number.abs().compareTo(BigDecimal.TEN.pow(digits)) >= 0) return "整数位超过目标精度";
                }
                if (minimum != null && number.compareTo(new BigDecimal(minimum)) < 0)
                    return "小于目标最小值 " + minimum;
                if (maximum != null && number.compareTo(new BigDecimal(maximum)) > 0)
                    return "大于目标最大值 " + maximum;
                return null;
            }
            if ("URL".equals(source) && textTarget(target)) {
                JsonNode link = json.readTree(oldValue);
                if (!link.isObject() || !link.path("link").isTextual()) return "原链接结构无效";
                if (!link.path("text").asText("").isEmpty()) return "链接包含单独的显示文字，无法仅提取地址而保留全部信息";
                return textFailure(link.path("link").asText(), toSqlType, pattern);
            }
            if (textSource && textTarget(target)) return textFailure(oldValue, toSqlType, pattern);
            if ("SELECT".equals(source) && "MULTI_SELECT".equals(target)) return null;
            if ("MULTI_SELECT".equals(source) && "SELECT".equals(target)) {
                JsonNode values = json.readTree(oldValue);
                if (!values.isArray()) return "原多选值不是选项集合";
                if (values.size() > 1) return "一条记录有多个选项，不能完整转为单选";
                if (values.size() == 1 && !values.get(0).isTextual()) return "原选项编码无效";
                return null;
            }
            return "这两种字段类型没有无损保留规则";
        } catch (ArithmeticException | JsonProcessingException | NumberFormatException exception) {
            return "原值不符合目标字段格式";
        }
    }

    private static boolean textTarget(String type) {
        return "TEXT".equals(type) || "TEXTAREA".equals(type);
    }

    private static String textFailure(String value, String toSqlType, String pattern) {
        Matcher limit = VARCHAR.matcher(toSqlType);
        if (limit.matches()
                && value.codePointCount(0, value.length()) > Integer.parseInt(limit.group(1)))
            return "字符数超过目标长度 " + limit.group(1);
        if (pattern != null)
            try {
                if (!Pattern.compile(pattern).matcher(value).find()) return "不符合目标正则规则";
            } catch (java.util.regex.PatternSyntaxException ignored) {
                // 目标正则由 PostgreSQL 作最终判定；Java 不认识的数据库语法不影响旧值展示。
            }
        return null;
    }
}
