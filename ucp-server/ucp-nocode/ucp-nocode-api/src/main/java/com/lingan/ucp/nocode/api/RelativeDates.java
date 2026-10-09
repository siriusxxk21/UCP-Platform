package com.lingan.ucp.nocode.api;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.common.dto.DynamicConditionDTO;
import com.lingan.ucp.nocode.enums.FieldTypeEnum;
import com.lingan.ucp.nocode.enums.RelativeDateEnum;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 条件里的相对日期（今天、本周、过去 N 天……）：识别、校验、按执行当时的日期换算成区间，再换成参数化的比较边界。
 *
 * <p>存储形状只有一种：条件值是 {"relative": 编码} 或 {"relative": "PAST_N_DAYS", "n":
 * 7}。视图固定范围、记录权限、业务动作（DataScope）、
 * 统计与数据视图条件（DynamicConditionDTO）、引用筛选（FieldRules.Condition）共用本类。基线三处保存校验都拒绝对象形状的值，所以存量配置里不会有它：
 * 新分支一律以 {@link #isRelative} 为入口，具体日期（字符串 / [起, 止]）仍走原路径。
 *
 * <p>区间左闭右开 [起, 止)；周从周一开始。比较方式的含义：等于 / 在范围内 = 落在区间内；不等于 = 不在区间内；小于（早于）= 早于区间开始； 小于等于 =
 * 早于区间结束；大于（晚于）= 不早于区间结束；大于等于 = 不早于区间开始。日期时间字段的边界取当天 00:00（Asia/Shanghai）： 带时区的列按 +08:00
 * 的时刻比较，不带时区的列按墙上时间比较，与公式 TODAY()（FormulaDates）同一口径。
 */
public final class RelativeDates {
    /** 与 FormulaDates.ZONE 相同（metadata 依赖 api、反向不行，故在此另立；用例钉住两者相等）。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 本机端到端验证换日用：JVM 启动参数 -Dnocode.relative-date.today=YYYY-MM-DD 时以它为「今天」。线上启动参数里没有它，即系统时钟。 */
    public static final String TODAY_PROPERTY = "nocode.relative-date.today";

    public static final String KEY = "relative";
    public static final String COUNT_KEY = "n";
    public static final int MAX_DAYS = 3650;

    /** 能与相对日期组合的比较方式；属于任意一个、包含、为空等不能。 */
    public static final Set<String> OPERATORS =
            Set.of("eq", "neq", "gt", "gte", "lt", "lte", "between");

    private RelativeDates() {}

    /** 一条已校验的相对日期。n 只在过去 / 未来 N 天时有值。 */
    public record Spec(RelativeDateEnum kind, Integer n) {}

    /** 左闭右开区间 [start, end)。 */
    public record Range(LocalDate start, LocalDate end) {}

    /** 一个边界比较：operator 只有 lt / gte。 */
    public record Bound(String operator, LocalDate date) {}

    /** 一组边界：any 为真时任一满足（不等于），否则全部满足。 */
    public record Bounds(boolean any, List<Bound> parts) {}

    /** 值是不是相对日期（对象且带 relative 键）；不是的值一律交给原有的具体值路径。 */
    public static boolean isRelative(Object value) {
        return value instanceof Map<?, ?> map && map.containsKey(KEY);
    }

    /** 解析并校验相对日期；不认识的编码、多余的键、缺少或越界的天数都拒绝，不猜。 */
    public static Spec parse(Object value) {
        if (!(value instanceof Map<?, ?> map) || !map.containsKey(KEY)) throw invalid("相对日期格式无效");
        for (Object key : map.keySet())
            if (!KEY.equals(key) && !COUNT_KEY.equals(key))
                throw invalid("相对日期格式无效：多余的键「" + key + "」");
        Object code = map.get(KEY);
        if (!(code instanceof String text) || !RelativeDateEnum.containsCode(text))
            throw invalid("相对日期「" + code + "」无效");
        RelativeDateEnum kind = RelativeDateEnum.fromCode(text);
        Object n = map.get(COUNT_KEY);
        if (!kind.counted()) {
            if (n != null) throw invalid("「" + kind.getLabel() + "」不需要天数");
            return new Spec(kind, null);
        }
        Integer days = days(n);
        if (days == null || days < 1 || days > MAX_DAYS)
            throw invalid("「" + kind.getLabel() + "」的天数应为 1–" + MAX_DAYS + " 的整数");
        return new Spec(kind, days);
    }

    private static Integer days(Object n) {
        if (!(n instanceof Number number)) return null;
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    /** 执行当时的「今天」（Asia/Shanghai）；只有本机验证时由系统属性指定。 */
    public static LocalDate today() {
        String fixed = System.getProperty(TODAY_PROPERTY);
        if (fixed != null && !fixed.isBlank()) return LocalDate.parse(fixed.trim());
        return LocalDate.now(ZONE);
    }

    /** 按 today 换算区间。 */
    public static Range range(Spec spec, LocalDate today) {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate month = today.withDayOfMonth(1);
        LocalDate year = today.withDayOfYear(1);
        return switch (spec.kind()) {
            case TODAY -> day(today);
            case YESTERDAY -> day(today.minusDays(1));
            case TOMORROW -> day(today.plusDays(1));
            case THIS_WEEK -> new Range(monday, monday.plusWeeks(1));
            case LAST_WEEK -> new Range(monday.minusWeeks(1), monday);
            case NEXT_WEEK -> new Range(monday.plusWeeks(1), monday.plusWeeks(2));
            case THIS_MONTH -> new Range(month, month.plusMonths(1));
            case LAST_MONTH -> new Range(month.minusMonths(1), month);
            case NEXT_MONTH -> new Range(month.plusMonths(1), month.plusMonths(2));
            case THIS_YEAR -> new Range(year, year.plusYears(1));
            case LAST_YEAR -> new Range(year.minusYears(1), year);
            case PAST_N_DAYS -> new Range(today.minusDays(spec.n() - 1L), today.plusDays(1));
            case NEXT_N_DAYS -> new Range(today, today.plusDays(spec.n()));
        };
    }

    private static Range day(LocalDate date) {
        return new Range(date, date.plusDays(1));
    }

    /** 只有日期与日期时间字段能用相对日期（时间字段没有「哪一天」）。 */
    public static boolean supports(FieldTypeEnum type) {
        return type == FieldTypeEnum.DATE || type == FieldTypeEnum.DATETIME;
    }

    /** 保存与执行共用的组合校验：字段类型、比较方式、值形状。 */
    public static Spec check(FieldDefinition field, String operator, Object value) {
        if (!FieldTypeEnum.containsCode(field.type())
                || !supports(FieldTypeEnum.fromCode(field.type())))
            throw invalid("相对日期只能用于日期或日期时间字段：" + field.name());
        if (operator == null || !OPERATORS.contains(operator))
            throw invalid("「" + field.name() + "」的这种比较方式不能使用相对日期");
        return parse(value);
    }

    /** 比较方式换成区间边界（见类说明）。 */
    public static Bounds bounds(String operator, Range range) {
        return switch (operator) {
            case "eq", "between" ->
                    new Bounds(
                            false,
                            List.of(new Bound("gte", range.start()), new Bound("lt", range.end())));
            case "neq" ->
                    new Bounds(
                            true,
                            List.of(new Bound("lt", range.start()), new Bound("gte", range.end())));
            case "lt" -> new Bounds(false, List.of(new Bound("lt", range.start())));
            case "lte" -> new Bounds(false, List.of(new Bound("lt", range.end())));
            case "gt" -> new Bounds(false, List.of(new Bound("gte", range.end())));
            case "gte" -> new Bounds(false, List.of(new Bound("gte", range.start())));
            default -> throw invalid("这种比较方式不能使用相对日期：" + operator);
        };
    }

    /** 边界日期按字段的列类型取值：日期 → 日期；带时区的日期时间 → 当天 00:00 +08:00；不带时区 → 当天 00:00 墙上时间。 */
    public static Object boundValue(
            FieldDefinition field, DataCenter.FieldOptions options, LocalDate date) {
        if (FieldTypeEnum.DATE.matches(field.type())) return date;
        if (withZone(options)) return date.atStartOfDay(ZONE).toOffsetDateTime();
        return date.atStartOfDay();
    }

    private static boolean withZone(DataCenter.FieldOptions options) {
        return options != null
                && options.nativeType() != null
                && options.nativeType().contains("with time zone");
    }

    /** 底座条件树里的一个相对日期叶子，展开成同字段的一组边界条件（值已是类型化边界，不再经过字符串转换）。返回的是一个条件组，由调用方原位替换叶子。 */
    public static DynamicConditionDTO.Item expand(
            FieldDefinition field,
            DataCenter.FieldOptions options,
            String operator,
            Object value,
            LocalDate today) {
        Spec spec = check(field, operator, value);
        Bounds bounds = bounds(operator, range(spec, today));
        List<DynamicConditionDTO.Item> leaves = new ArrayList<>();
        for (Bound bound : bounds.parts()) {
            var leaf = new DynamicConditionDTO.Item();
            leaf.setType("condition");
            leaf.setField(field.id());
            leaf.setOperator(bound.operator());
            leaf.setValue(boundValue(field, options, bound.date()));
            leaves.add(leaf);
        }
        var group = new DynamicConditionDTO.Item();
        group.setType("group");
        group.setGroupLogic(
                bounds.any() ? DynamicConditionDTO.Logic.OR : DynamicConditionDTO.Logic.AND);
        group.setGroupItems(leaves);
        return group;
    }

    /**
     * 内存判断（写入后的范围检查、业务动作条件）：actual 是已按字段类型转换的值（LocalDate / LocalDateTime /
     * OffsetDateTime）。空值不满足任何比较， 与 DataScope 对具体值的口径一致。
     */
    public static boolean matches(
            FieldDefinition field,
            DataCenter.FieldOptions options,
            String operator,
            Object value,
            Object actual,
            LocalDate today) {
        Spec spec = check(field, operator, value);
        if (actual == null) return false;
        Bounds bounds = bounds(operator, range(spec, today));
        boolean any = false;
        boolean all = true;
        for (Bound bound : bounds.parts()) {
            int c = compare(actual, boundValue(field, options, bound.date()));
            boolean ok = "lt".equals(bound.operator()) ? c < 0 : c >= 0;
            any |= ok;
            all &= ok;
        }
        return bounds.any() ? any : all;
    }

    private static int compare(Object actual, Object bound) {
        if (actual instanceof OffsetDateTime x && bound instanceof OffsetDateTime y)
            return x.toInstant().compareTo(y.toInstant());
        if (actual instanceof LocalDateTime x && bound instanceof LocalDateTime y)
            return x.compareTo(y);
        if (actual instanceof LocalDate x && bound instanceof LocalDate y) return x.compareTo(y);
        throw invalid("相对日期无法与该字段的值比较");
    }
}
