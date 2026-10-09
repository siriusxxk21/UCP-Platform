package com.richuang.os.nocode.runtime.service.rules;

import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.formula.MoneyRounding;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 数据联动的纯值层：多行归约、定点求和与金额取整。不查库、不依赖 Spring。
 *
 * <p>四档语义逐条对照老系统 linkage-value.ts：拼接用 ASCII 逗号并跳过空值；取第一行取调用方给定顺序的首格；报错档命中多于 1 行即失败；求和空格不当 0、全空得
 * null、无法解析的格子失败。取整按目标字段是否 MONEY 判定（不再按来源），缺省 FLOOR。
 */
public final class LinkageValues {
    public static final String CONCAT_SEPARATOR = ",";
    private static final Pattern DECIMAL_TEXT = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Set<FieldTypeEnum> MULTI_VALUED =
            Set.of(
                    FieldTypeEnum.MULTI_SELECT,
                    FieldTypeEnum.IMAGE,
                    FieldTypeEnum.ATTACHMENT,
                    FieldTypeEnum.REGION,
                    FieldTypeEnum.CASCADE);

    /** 文本目标：单行与多行文本。来源可以是自动编号、链接（2026-10-01），格子先统一成文本再归约。 */
    private static final Set<FieldTypeEnum> TEXT_TARGETS =
            Set.of(FieldTypeEnum.TEXT, FieldTypeEnum.TEXTAREA);

    /** 与 RecordValues 的文本长度硬上限一致；字段自身的长度上限更小时以字段为准。 */
    static final int TEXT_HARD_LIMIT = 100000;

    private static final int PREVIEW_LENGTH = 20;

    private LinkageValues() {}

    /** 归约结果：只有 APPLIED 时 value 可能非 null；其它状态附带一句人话。 */
    public record Outcome(String state, Object value, String message) {
        public boolean applied() {
            return FieldRuleStateEnum.APPLIED.matches(state);
        }

        static Outcome ok(Object value) {
            return new Outcome(FieldRuleStateEnum.APPLIED.getCode(), value, null);
        }

        static Outcome fail(FieldRuleStateEnum state, String message) {
            return new Outcome(state.getCode(), null, message);
        }
    }

    /** 空值按缺省档 CONCAT；不认识的编码原样返回，由调用方 fail-closed，不猜。 */
    public static String mode(String multiRow) {
        return multiRow == null || multiRow.isEmpty()
                ? LinkageMultiRowEnum.CONCAT.getCode()
                : multiRow;
    }

    /** 数值来源：数值类型，结果为数值的公式字段，以及明细汇总字段。 */
    public static boolean numeric(FieldDefinition field, DataCenter.FieldOptions options) {
        var type = FieldTypeEnum.fromCode(field.type());
        if (type.isNumeric() || type == FieldTypeEnum.SUMMARY) return true;
        return type == FieldTypeEnum.FORMULA
                && options != null
                && Set.of(FieldTypeEnum.INTEGER.getCode(), FieldTypeEnum.DECIMAL.getCode())
                        .contains(Objects.toString(options.resultType(), ""));
    }

    /** 先判档位再查库：返回 null 表示档位可用，否则返回对应失败。 */
    public static Outcome checkMode(String multiRow, boolean numericSource, String rounding) {
        String mode = mode(multiRow);
        if (!LinkageMultiRowEnum.containsCode(mode))
            return Outcome.fail(
                    FieldRuleStateEnum.MULTI_ROW_MODE_UNKNOWN,
                    "多行匹配档位「" + mode + "」不在四档里（拼接成一行 / 取第一行 / 求和 / 报错），不填值、不猜");
        if (LinkageMultiRowEnum.SUM.matches(mode) && !numericSource)
            return Outcome.fail(FieldRuleStateEnum.MULTI_ROW_MODE_REJECTED, "「求和」只适用于数值或金额来源，不填值");
        return checkRounding(rounding);
    }

    /** 取整编码只有三档；未知编码 fail-closed 并点名，不回落缺省档。 */
    public static Outcome checkRounding(String rounding) {
        if (rounding != null && !MoneyRoundingEnum.containsCode(rounding))
            return Outcome.fail(
                    FieldRuleStateEnum.ROUNDING_MODE_UNKNOWN,
                    "取整方式「" + rounding + "」无效，可选：四舍五入 / 向下取整 / 去掉小数，不填值");
        return null;
    }

    /**
     * 命中的多行 → 一个值。cells 的顺序由调用方给定（create_time、主键升序）；调用方已排除 0 行。
     *
     * <p>恰好 1 行时四档结果相同（求和仍按金额目标取整），避免「一行不取整、两行取整」的口径分裂。
     */
    public static Outcome reduce(
            String multiRow,
            List<?> cells,
            boolean numericSource,
            FieldDefinition target,
            String rounding) {
        var check = checkMode(multiRow, numericSource, rounding);
        if (check != null) return check;
        if (textTarget(target)) cells = cells.stream().map(LinkageValues::textCell).toList();
        var mode = LinkageMultiRowEnum.fromCode(mode(multiRow));
        Object value;
        switch (mode) {
            case ERROR -> {
                if (cells.size() > 1)
                    return Outcome.fail(
                            FieldRuleStateEnum.MULTI_ROW_ERROR,
                            "命中 " + cells.size() + " 行，而多行匹配配的是「报错」，不填值");
                value = cells.isEmpty() ? null : cells.getFirst();
            }
            case FIRST -> value = cells.isEmpty() ? null : cells.getFirst();
            case SUM -> {
                BigDecimal sum = null;
                for (Object cell : cells) {
                    if (empty(cell)) continue;
                    var number = decimal(cell);
                    if (number == null)
                        return Outcome.fail(
                                FieldRuleStateEnum.SUM_NOT_NUMERIC,
                                "命中的行里有一格「" + text(cell) + "」不是数，不当 0 混进去求和");
                    sum = sum == null ? number : sum.add(number);
                }
                value = sum;
            }
            default -> {
                var parts = cells.stream().filter(c -> !empty(c)).toList();
                if (parts.isEmpty()) value = null;
                else if (parts.size() == 1 && !(parts.getFirst() instanceof Collection<?>))
                    value = parts.getFirst();
                else
                    value =
                            String.join(
                                    CONCAT_SEPARATOR,
                                    parts.stream().map(LinkageValues::text).toList());
            }
        }
        if (value instanceof Collection<?>
                && !MULTI_VALUED.contains(FieldTypeEnum.fromCode(target.type())))
            return Outcome.fail(
                    FieldRuleStateEnum.UNSUPPORTED_VALUE_KIND,
                    "来源这一格是多个值，而「" + target.name() + "」只能放一个值，不填值");
        return Outcome.ok(round(value, target, rounding));
    }

    /** 目标为 MONEY 且值是数：按取整方式取成十进制整数串（整数不变）。目标不是 MONEY 时保持小数；非数值原样返回，由类型转换判定是否匹配。 */
    public static Object round(Object value, FieldDefinition target, String rounding) {
        if (value == null || !FieldTypeEnum.MONEY.matches(target.type())) return value;
        var number = decimal(value);
        if (number == null) return value;
        return MoneyRounding.settle(number, MoneyRoundingEnum.of(rounding).mode()).toPlainString();
    }

    public static boolean textTarget(FieldDefinition target) {
        return FieldTypeEnum.containsCode(target.type())
                && TEXT_TARGETS.contains(FieldTypeEnum.fromCode(target.type()));
    }

    /**
     * 文本目标收到的一格统一成文本：链接取地址（显示文字不带入），数字（数据库自增的自动编号等）取十进制字面量。
     *
     * <p>已是文本、空值与多值格原样返回，多值格仍由归约判定能否放进目标。
     */
    static Object textCell(Object cell) {
        if (cell instanceof Map<?, ?> link && link.get("link") instanceof String address)
            return address;
        return cell instanceof Number ? text(cell) : cell;
    }

    /**
     * 文本目标的长度复核：结果超过字段长度上限时返回一句人话（不截断、不填值），否则返回 null。
     *
     * <p>与数值超出小数位同口径：宁可不带出，也不悄悄改值。
     */
    public static String tooLong(FieldDefinition target, Object value) {
        if (!textTarget(target) || !(value instanceof String text)) return null;
        int limit =
                target.length() == null
                        ? TEXT_HARD_LIMIT
                        : Math.min(target.length(), TEXT_HARD_LIMIT);
        if (text.length() <= limit) return null;
        String preview =
                text.length() > PREVIEW_LENGTH ? text.substring(0, PREVIEW_LENGTH) + "…" : text;
        return "结果「"
                + preview
                + "」共 "
                + text.length()
                + " 个字符，超过字段「"
                + target.name()
                + "」的长度上限 "
                + limit
                + "，不填值（不截断）";
    }

    static boolean empty(Object cell) {
        return cell == null
                || cell instanceof String s && s.isEmpty()
                || cell instanceof Collection<?> c && c.isEmpty();
    }

    /** 数字或十进制字面量（允许首尾空白）；其它一律 null，不当 0。 */
    static BigDecimal decimal(Object cell) {
        if (cell instanceof BigDecimal d) return d;
        if (cell instanceof Number || cell instanceof String) {
            String text = cell.toString().trim();
            if (cell instanceof Double || cell instanceof Float) {
                double v = ((Number) cell).doubleValue();
                if (Double.isNaN(v) || Double.isInfinite(v)) return null;
                return BigDecimal.valueOf(v);
            }
            return DECIMAL_TEXT.matcher(text).matches() ? new BigDecimal(text) : null;
        }
        return null;
    }

    /** 与老系统 String(cell) 同形：数组按元素逗号连接，数值不用科学计数法。 */
    static String text(Object cell) {
        if (cell == null) return "";
        if (cell instanceof BigDecimal d) return d.toPlainString();
        if (cell instanceof Collection<?> list)
            return String.join(",", list.stream().map(LinkageValues::text).toList());
        return cell.toString();
    }
}
