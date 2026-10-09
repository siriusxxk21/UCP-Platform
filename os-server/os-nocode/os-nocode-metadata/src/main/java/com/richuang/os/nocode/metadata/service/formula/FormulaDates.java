package com.richuang.os.nocode.metadata.service.formula;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.framework.mybatis.core.metadata.PostgreSqlCommands.*;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.enums.FieldTypeEnum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公式里的日期函数与日期加减。函数名、参数顺序跟 Excel / 钉钉一致，大小写不敏感。
 *
 * <ul>
 *   <li>求值（保存时计算、读取时计算、公式默认值、试算走这里）：日期一律按「日期部分」算，日期时间字段取它写出来的那一天，不做时区换算； 日期结果用 yyyy-MM-dd 文本表示，与
 *       DATE 字段的值同形，可以直接比较。
 *   <li>保存对象时的类型检查：只报与日期有关的错，文案点名出错的函数。
 *   <li>落库的本行公式（数据库生成列）：把「日期 − 日期」「日期 ± 天数」改写成显式函数，由 PostgreSqlCommands 渲染成不可变表达式， 结果与上面的求值逐项相同。
 * </ul>
 *
 * 任一参数为空 ⇒ 结果为空；退房早于入住这类倒挂 ⇒ 负数，不报错。
 */
public final class FormulaDates {
    private FormulaDates() {}

    /** 「今天」的日界与自动编号取当天的口径相同（RecordAutoNumbers）。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 落库改写后的内部函数名：日期 ± 天数。不出现在公式文本里（解析器不认）。 */
    public static final String DATE_ADD = "date_add";

    private static final Map<String, int[]> ARITY =
            Map.ofEntries(
                    Map.entry("year", new int[] {1, 1}),
                    Map.entry("month", new int[] {1, 1}),
                    Map.entry("day", new int[] {1, 1}),
                    Map.entry("weekday", new int[] {1, 2}),
                    Map.entry("days", new int[] {2, 2}),
                    Map.entry("datedif", new int[] {3, 3}),
                    Map.entry("eomonth", new int[] {1, 2}),
                    Map.entry("edate", new int[] {2, 2}),
                    Map.entry("date", new int[] {3, 3}),
                    Map.entry("today", new int[] {0, 0}),
                    Map.entry("now", new int[] {0, 0}));

    private static final Pattern DATE_TEXT =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})(?:[T ]\\d{2}:\\d{2}.*)?");

    /** 公式文本里可以写的日期函数（小写）。 */
    public static boolean function(String name) {
        return ARITY.containsKey(name);
    }

    public static boolean noArguments(String name) {
        return function(name) && ARITY.get(name)[1] == 0;
    }

    public static void checkArity(String name, int count) {
        int[] range = ARITY.get(name);
        if (range == null || count >= range[0] && count <= range[1]) return;
        throw invalid(
                upper(name)
                        + " 的参数个数不对：需要 "
                        + (range[0] == range[1] ? range[0] + "" : range[0] + " 到 " + range[1])
                        + " 个，写了 "
                        + count
                        + " 个");
    }

    private static String upper(String name) {
        return name.toUpperCase(Locale.ROOT);
    }

    // ── 求值 ──

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** 能认成日期的值：日期 / 日期时间对象，或 yyyy-MM-dd 开头的文本（DATE、DATETIME 字段的值就是这种文本）。数字、布尔不算。 */
    public static LocalDate dateOrNull(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) return null;
        if (value instanceof LocalDate date) return date;
        if (value instanceof LocalDateTime time) return time.toLocalDate();
        if (value instanceof OffsetDateTime time) return time.toLocalDate();
        if (value instanceof ZonedDateTime time) return time.toLocalDate();
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        if (value instanceof java.sql.Timestamp time) return time.toLocalDateTime().toLocalDate();
        if (value instanceof java.util.Date date)
            return date.toInstant().atZone(ZONE).toLocalDate();
        Matcher matcher = DATE_TEXT.matcher(value.toString());
        if (!matcher.matches()) return null;
        try {
            return LocalDate.parse(matcher.group(1));
        } catch (DateTimeParseException notADate) {
            return null;
        }
    }

    private static LocalDate date(String function, Object value) {
        LocalDate date = dateOrNull(value);
        if (date == null) throw invalid(upper(function) + " 需要日期，实际是「" + value + "」");
        return date;
    }

    /** 天数、月数、年月日都按整数算，小数部分直接去掉（与落库公式里的 trunc 相同）。 */
    private static int whole(String function, Object value) {
        try {
            return FormulaEvaluator.number(value).setScale(0, RoundingMode.DOWN).intValueExact();
        } catch (ArithmeticException tooLarge) {
            throw invalid(upper(function) + " 的数字参数超出范围：" + value);
        }
    }

    private static BigDecimal number(long value) {
        return BigDecimal.valueOf(value);
    }

    /** 「+」「−」两边只要有一边是日期，就按日期加减算，不再当数字。 */
    public static boolean dateArithmetic(String operator, Object left, Object right) {
        return ("+".equals(operator) || "-".equals(operator))
                && (dateOrNull(left) != null || dateOrNull(right) != null);
    }

    public static Object arithmetic(String operator, Object left, Object right) {
        LocalDate a = dateOrNull(left), b = dateOrNull(right);
        try {
            if (a != null && b != null) {
                if ("+".equals(operator)) throw invalid("两个日期不能相加；要算相差天数请用减号或 DAYS");
                return number(ChronoUnit.DAYS.between(b, a));
            }
            if (a != null) {
                int days = whole("日期加减", right);
                return a.plusDays("+".equals(operator) ? days : -(long) days).toString();
            }
            if ("-".equals(operator)) throw invalid("数字不能减日期；要算相差天数请用「日期 − 日期」或 DAYS");
            return b.plusDays(whole("日期加减", left)).toString();
        } catch (DateTimeException outOfRange) {
            throw invalid("日期加减的结果超出可表示的范围");
        }
    }

    public static Object evaluate(FunctionValue call, Function<String, Object> fields) {
        String name = call.name();
        if ("today".equals(name)) return today().toString();
        if ("now".equals(name)) return LocalDateTime.now(ZONE).withNano(0).toString();
        List<Object> args = new ArrayList<>();
        for (Expression argument : call.arguments()) {
            Object value = FormulaEvaluator.evaluate(argument, fields);
            if (value == null) return null;
            args.add(value);
        }
        try {
            return switch (name) {
                case "year" -> number(date(name, args.get(0)).getYear());
                case "month" -> number(date(name, args.get(0)).getMonthValue());
                case "day" -> number(date(name, args.get(0)).getDayOfMonth());
                case "weekday" ->
                        number(
                                weekday(
                                        date(name, args.get(0)),
                                        args.size() == 1 ? 1 : whole(name, args.get(1))));
                case "days" ->
                        number(
                                ChronoUnit.DAYS.between(
                                        date(name, args.get(1)), date(name, args.get(0))));
                case "datedif" -> {
                    LocalDate start = date(name, args.get(0)), end = date(name, args.get(1));
                    yield number(
                            switch (unit(args.get(2))) {
                                case "D" -> ChronoUnit.DAYS.between(start, end);
                                case "M" -> ChronoUnit.MONTHS.between(start, end);
                                default -> ChronoUnit.YEARS.between(start, end);
                            });
                }
                case "eomonth" ->
                        date(name, args.get(0))
                                .plusMonths(args.size() == 1 ? 0 : whole(name, args.get(1)))
                                .with(TemporalAdjusters.lastDayOfMonth())
                                .toString();
                case "edate" ->
                        date(name, args.get(0)).plusMonths(whole(name, args.get(1))).toString();
                case "date" -> {
                    int year = whole(name, args.get(0));
                    if (year < 1 || year > 9999) throw invalid("DATE 的年份须在 1 到 9999 之间：" + year);
                    LocalDate result =
                            LocalDate.of(year, 1, 1)
                                    .plusMonths(whole(name, args.get(1)) - 1L)
                                    .plusDays(whole(name, args.get(2)) - 1L);
                    if (result.getYear() < 1 || result.getYear() > 9999)
                        throw invalid("DATE 算出的日期超出 1 到 9999 年");
                    yield result.toString();
                }
                default -> throw invalid("不支持的公式函数");
            };
        } catch (DateTimeException outOfRange) {
            throw invalid(upper(name) + " 的结果超出可表示的日期范围");
        }
    }

    /** 与 Excel 相同：1（缺省）周日=1…周六=7；2 周一=1…周日=7；3 周一=0…周日=6。 */
    private static int weekday(LocalDate date, int type) {
        int iso = date.getDayOfWeek().getValue();
        return switch (type) {
            case 1 -> iso % 7 + 1;
            case 2 -> iso;
            case 3 -> iso - 1;
            default -> throw invalid("WEEKDAY 的第二个参数只能是 1、2 或 3");
        };
    }

    private static String unit(Object value) {
        String unit = value.toString().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("D", "M", "Y").contains(unit))
            throw invalid("DATEDIF 的第三个参数只能是 \"D\"（天）、\"M\"（整月）或 \"Y\"（整年）");
        return unit;
    }

    // ── 类型 ──

    public enum Kind {
        NUMBER,
        TEXT,
        BOOLEAN,
        DATE,
        DATETIME,
        /** 带时区的日期时间（只有纳管表才有）：不能进落库公式的日期函数。 */
        ZONED,
        /** 引用、人员、多选等既不是日期也不是数字、文字的字段。 */
        OTHER,
        NULL,
        /** 没有类型信息：不报错。 */
        UNKNOWN;

        boolean date() {
            return this == DATE || this == DATETIME || this == ZONED;
        }
    }

    public static Kind kind(FieldDefinition field, DataCenter.FieldOptions options) {
        String type = field.type();
        if (FieldTypeEnum.fromCode(type).isComputed())
            type = options == null ? null : options.resultType();
        if (type == null || !FieldTypeEnum.containsCode(type)) return Kind.UNKNOWN;
        if (FieldTypeEnum.DATETIME.matches(type)
                && options != null
                && options.nativeType() != null
                && options.nativeType().contains("with time zone")) return Kind.ZONED;
        return kind(type);
    }

    public static Kind kind(String type) {
        if (type == null || !FieldTypeEnum.containsCode(type)) return Kind.UNKNOWN;
        FieldTypeEnum value = FieldTypeEnum.fromCode(type);
        if (value == FieldTypeEnum.DATE) return Kind.DATE;
        if (value == FieldTypeEnum.DATETIME) return Kind.DATETIME;
        if (value == FieldTypeEnum.BOOLEAN) return Kind.BOOLEAN;
        if (value.isNumeric()) return Kind.NUMBER;
        return switch (value) {
            case TEXT, TEXTAREA, RICH_TEXT, SELECT, AUTO_NUMBER, UUID, TIME -> Kind.TEXT;
            case FORMULA, SUMMARY -> Kind.UNKNOWN;
            default -> Kind.OTHER;
        };
    }

    /**
     * 一次检查的上下文。kinds / labels 的键是语法树里 FieldValue 带的那个值（各调用点传给解析器的映射值）。
     *
     * @param volatileAllowed 能不能用 TODAY() / NOW()：落库的值会过期，只有读取时计算与公式默认值可以
     * @param generated 是不是数据库生成列（本行公式）
     */
    public record Context(
            Map<String, Kind> kinds,
            Map<String, String> labels,
            boolean volatileAllowed,
            boolean generated) {}

    /** 本行公式（生成列）：只看同表字段，键是物理列名（与结构编译传给解析器的映射一致）。落库，不能用 TODAY() / NOW()。 */
    public static Context generated(
            List<FieldDefinition> fields, Map<String, DataCenter.FieldOptions> options) {
        Map<String, Kind> kinds = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (FieldDefinition field : fields) {
            DataCenter.FieldOptions option =
                    options.getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
            String column = option.columnName() == null ? field.code() : option.columnName();
            kinds.put(column, kind(field, option));
            labels.put(column, field.name());
        }
        return new Context(kinds, labels, false, true);
    }

    /** 「包含计算结果」的公式与顺序计算：键是字段 id（相邻记录是 previous:id）。只有读取时计算能用 TODAY() / NOW()。 */
    public static Context calculated(DataCenter.Definition definition, boolean live) {
        Map<String, Kind> kinds = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (FieldDefinition field : definition.fields()) {
            Kind kind = kind(field, definition.fieldOptions().get(field.id()));
            kinds.put(field.id(), kind);
            kinds.put("previous:" + field.id(), kind);
            labels.put(field.id(), field.name());
            labels.put("previous:" + field.id(), "相邻记录的" + field.name());
        }
        return new Context(kinds, labels, live, false);
    }

    /** 试算与公式默认值：键是字段编码，值是字段类型编码。 */
    public static Context byCode(Map<String, String> types) {
        Map<String, Kind> kinds = new HashMap<>();
        types.forEach((code, type) -> kinds.put(code, kind(type)));
        return new Context(kinds, Map.of(), true, false);
    }

    /**
     * @param derived 这个日期是日期函数或日期加减算出来的（不是字段本身）
     * @param literal 写成日期样子的文字常量，例如 '2026-07-30'
     */
    private record Typed(Kind kind, boolean derived, boolean literal) {
        static Typed of(Kind kind) {
            return new Typed(kind, false, false);
        }

        boolean date() {
            return kind.date() || literal;
        }

        boolean known() {
            return kind != Kind.UNKNOWN && kind != Kind.NULL;
        }
    }

    /** 整条公式的结果是不是「算出来的日期」（公式字段的结果类型里没有日期，试算用它标注结果类型）。 */
    public static boolean dateResult(Expression expression, Context context) {
        try {
            Typed root = type(expression, context);
            return root.kind.date() && root.derived;
        } catch (ServiceException typeError) {
            // 试算只借类型推断标注结果类型；类型检查的报错属于保存对象那一步，不在试算里抛。
            return false;
        }
    }

    /**
     * 公式字段（本行公式、保存时计算、读取时计算）保存时的检查。结果类型只有文本 / 整数 / 小数 / 金额， 日期只能当中间值：再套一层
     * YEAR/MONTH/DAY、相减得天数，或者用来比较。
     */
    public static void checkField(String fieldName, Expression expression, Context context) {
        try {
            Typed root = type(expression, context);
            if (root.kind.date() && root.derived)
                throw invalid(
                        "这条公式最后算出来的是一个日期，而公式字段的结果只能是文本、整数、小数或金额。"
                                + "日期只能当中间值用：再套一层 YEAR / MONTH / DAY 取出数字，或者两个日期相减得天数，或者拿来比较");
        } catch (ServiceException error) {
            throw invalid("公式字段「" + fieldName + "」：" + message(error));
        }
    }

    /** 公式默认值保存时的检查。目标是数值字段时结果不能是日期；目标是日期或文本字段时日期结果照常写入。 */
    public static void checkDefault(
            String label, String targetType, Expression expression, Context context) {
        try {
            Typed root = type(expression, context);
            if (root.kind.date() && root.derived && kind(targetType) == Kind.NUMBER)
                throw invalid("公式算出来的是一个日期，不能填进数值字段；要天数请用两个日期相减或 DAYS");
        } catch (ServiceException error) {
            throw invalid(label + "：" + message(error));
        }
    }

    private static String message(ServiceException error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private static String label(Expression expression, Context context) {
        if (expression instanceof FieldValue field) {
            String label = context.labels().get(field.column());
            return "「" + (label == null ? field.column() : label) + "」";
        }
        if (expression instanceof NumberValue number) return "数字 " + number.value().toPlainString();
        if (expression instanceof TextValue text) return "文字「" + text.value() + "」";
        if (expression instanceof FunctionValue call) return upper(call.name()) + " 的结果";
        return "这一项";
    }

    private static String noun(Kind kind) {
        return switch (kind) {
            case NUMBER -> "数字";
            case TEXT -> "文字";
            case BOOLEAN -> "是/否";
            case DATE -> "日期";
            case DATETIME, ZONED -> "日期时间";
            case OTHER -> "既不是日期也不是数字的字段";
            default -> "空值";
        };
    }

    private static Typed type(Expression expression, Context context) {
        if (expression instanceof NumberValue) return Typed.of(Kind.NUMBER);
        if (expression instanceof BooleanValue) return Typed.of(Kind.BOOLEAN);
        if (expression instanceof NullValue) return Typed.of(Kind.NULL);
        if (expression instanceof TextValue text)
            return new Typed(Kind.TEXT, false, dateOrNull(text.value()) != null);
        if (expression instanceof FieldValue field)
            return Typed.of(context.kinds().getOrDefault(field.column(), Kind.UNKNOWN));
        if (expression instanceof BinaryValue binary) return binary(binary, context);
        if (expression instanceof FunctionValue call) return call(call, context);
        return Typed.of(Kind.UNKNOWN);
    }

    private static Typed binary(BinaryValue binary, Context context) {
        Typed left = type(binary.left(), context), right = type(binary.right(), context);
        String operator = binary.operator();
        if ("||".equals(operator)) {
            for (Typed side : List.of(left, right))
                if (context.generated() && side.kind.date() && side.derived)
                    throw invalid("本行公式里算出来的日期不能直接拼接文字；请用 YEAR / MONTH / DAY 取出数字后再拼");
            return Typed.of(Kind.TEXT);
        }
        if (Set.of("=", "<>", ">", ">=", "<", "<=").contains(operator)) {
            if (left.kind.date() && right.kind == Kind.NUMBER
                    || right.kind.date() && left.kind == Kind.NUMBER) {
                boolean leftDate = left.kind.date();
                throw invalid(
                        label(leftDate ? binary.left() : binary.right(), context)
                                + "是日期，不能直接和数字比较（另一边是"
                                + label(leftDate ? binary.right() : binary.left(), context)
                                + "）；请先用 YEAR / MONTH / DAY 取出数字，或者和另一个日期比");
            }
            return Typed.of(Kind.BOOLEAN);
        }
        boolean dates = left.kind.date() || right.kind.date();
        if ("*".equals(operator) || "/".equals(operator)) {
            if (dates)
                throw invalid(
                        label(left.kind.date() ? binary.left() : binary.right(), context)
                                + "是日期，不能做乘除；请先用 YEAR / MONTH / DAY 取出数字，或用两个日期相减得到天数");
            return Typed.of(Kind.NUMBER);
        }
        // 两边都不是日期：都知道类型时是数字；有一边没有类型信息时不下结论（它可能是日期）。
        if (!dates) return Typed.of(left.known() && right.known() ? Kind.NUMBER : Kind.UNKNOWN);
        zoned(binary.left(), left, context, "做日期加减");
        zoned(binary.right(), right, context, "做日期加减");
        if (left.date() && right.date()) {
            if ("+".equals(operator)) throw invalid("两个日期不能相加；要算相差天数请用减号或 DAYS");
            return Typed.of(Kind.NUMBER);
        }
        Typed other = left.kind.date() ? right : left;
        Expression otherNode = left.kind.date() ? binary.right() : binary.left();
        if (other.known() && other.kind != Kind.NUMBER)
            throw invalid(
                    "日期只能加减天数（数字）或减另一个日期，" + label(otherNode, context) + "是" + noun(other.kind));
        // 另一边没有类型信息：它是日期就得天数、是数字就得日期，这里不下结论。
        if (!other.known()) return Typed.of(Kind.UNKNOWN);
        if (right.kind.date() && "-".equals(operator))
            throw invalid("数字不能减日期；要算相差天数请用「日期 − 日期」或 DAYS");
        return new Typed(Kind.DATE, true, false);
    }

    private static void zoned(Expression node, Typed typed, Context context, String action) {
        if (context.generated() && typed.kind == Kind.ZONED)
            throw invalid(
                    label(node, context)
                            + "是带时区的日期时间字段，本行公式（落库）里不能对它"
                            + action
                            + "；请把计算方式改成「包含计算结果」并选择读取时计算");
    }

    private static void dateArgument(FunctionValue call, int index, Context context) {
        Expression node = call.arguments().get(index);
        Typed typed = type(node, context);
        zoned(node, typed, context, "用 " + upper(call.name()));
        if (typed.date() || !typed.known()) return;
        throw invalid(
                upper(call.name())
                        + " 的第 "
                        + (index + 1)
                        + " 个参数要填日期或日期时间字段（或其它日期函数的结果），"
                        + label(node, context)
                        + "是"
                        + noun(typed.kind)
                        + (typed.kind == Kind.TEXT && node instanceof TextValue
                                ? "，日期请写成 '2026-07-30' 这样"
                                : ""));
    }

    private static void numberArgument(FunctionValue call, int index, Context context) {
        Expression node = call.arguments().get(index);
        Typed typed = type(node, context);
        if (!typed.known() || typed.kind == Kind.NUMBER) return;
        throw invalid(
                upper(call.name())
                        + " 的第 "
                        + (index + 1)
                        + " 个参数要填数字，"
                        + label(node, context)
                        + "是"
                        + noun(typed.kind));
    }

    private static Typed call(FunctionValue call, Context context) {
        String name = call.name();
        List<Expression> args = call.arguments();
        switch (name) {
            case "today", "now" -> {
                if (!context.volatileAllowed())
                    throw invalid(
                            upper(name)
                                    + "() 每天的结果都不一样，而这个字段的值是保存下来的，存进去之后不会自己更新。"
                                    + "请把计算方式改成「包含计算结果」并选择读取时计算，或者改用公式默认值");
                return new Typed("today".equals(name) ? Kind.DATE : Kind.DATETIME, true, false);
            }
            case "year", "month", "day" -> {
                dateArgument(call, 0, context);
                return Typed.of(Kind.NUMBER);
            }
            case "weekday" -> {
                dateArgument(call, 0, context);
                if (args.size() == 2
                        && !(args.get(1) instanceof NumberValue type
                                && Set.of("1", "2", "3")
                                        .contains(
                                                type.value().stripTrailingZeros().toPlainString())))
                    throw invalid(
                            "WEEKDAY 的第二个参数请直接写 1、2 或 3（1：周日=1…周六=7；2：周一=1…周日=7；3：周一=0…周日=6）");
                return Typed.of(Kind.NUMBER);
            }
            case "days" -> {
                dateArgument(call, 0, context);
                dateArgument(call, 1, context);
                return Typed.of(Kind.NUMBER);
            }
            case "datedif" -> {
                dateArgument(call, 0, context);
                dateArgument(call, 1, context);
                if (!(args.get(2) instanceof TextValue unit)
                        || !Set.of("D", "M", "Y")
                                .contains(unit.value().trim().toUpperCase(Locale.ROOT)))
                    throw invalid("DATEDIF 的第三个参数请直接写 \"D\"（天）、\"M\"（整月）或 \"Y\"（整年）");
                return Typed.of(Kind.NUMBER);
            }
            case "eomonth", "edate" -> {
                dateArgument(call, 0, context);
                if (args.size() == 2) numberArgument(call, 1, context);
                return new Typed(Kind.DATE, true, false);
            }
            case "date" -> {
                for (int index = 0; index < 3; index++) numberArgument(call, index, context);
                return new Typed(Kind.DATE, true, false);
            }
            case DATE_ADD -> {
                return new Typed(Kind.DATE, true, false);
            }
            case "abs", "round" -> {
                Typed first = type(args.getFirst(), context);
                if (first.kind.date())
                    throw invalid(
                            upper(name)
                                    + " 只能用在数字上，"
                                    + label(args.getFirst(), context)
                                    + "是日期；请先用 YEAR / MONTH / DAY 取出数字，或用两个日期相减得到天数");
                for (Expression argument : args) type(argument, context);
                return Typed.of(Kind.NUMBER);
            }
            case "if", "coalesce" -> {
                List<Expression> branches = "if".equals(name) ? args.subList(1, args.size()) : args;
                if ("if".equals(name)) type(args.getFirst(), context);
                Typed result = Typed.of(Kind.NULL);
                for (Expression branch : branches)
                    result = unify(upper(name), result, type(branch, context));
                return result;
            }
            case "and", "or", "not", "isblank" -> {
                for (Expression argument : args) type(argument, context);
                return Typed.of(Kind.BOOLEAN);
            }
            default -> {
                for (Expression argument : args) type(argument, context);
                return Typed.of(Kind.TEXT);
            }
        }
    }

    private static Typed unify(String function, Typed a, Typed b) {
        if (!a.known()) return b.known() || a.kind == Kind.NULL ? b : a;
        if (!b.known()) return a;
        if (a.kind.date() && b.kind.date())
            return new Typed(
                    a.kind == Kind.DATE && b.kind == Kind.DATE ? Kind.DATE : Kind.DATETIME,
                    a.derived || b.derived,
                    false);
        // 只管新写法：算出来的日期与数字混在一起才报；字段本身是日期的存量写法照旧（不在这里拦）。
        if (a.kind.date() && a.derived && b.kind == Kind.NUMBER
                || b.kind.date() && b.derived && a.kind == Kind.NUMBER)
            throw invalid(function + " 的几个结果里有的是日期、有的是数字，要统一成一种");
        if (a.kind == b.kind) return new Typed(a.kind, false, a.literal && b.literal);
        return Typed.of(Kind.UNKNOWN);
    }

    // ── 落库公式的改写 ──

    /**
     * 本行公式（生成列）渲染前的改写：「日期 − 日期」→ days(左, 右)；「日期 ± 天数」「天数 + 日期」→ date_add(日期, ±天数)； EOMONTH 省略的月数补
     * 0。其余原样。先经过 checkField，类型不合的不会走到这里。
     */
    public static Expression lower(Expression expression, Context context) {
        if (expression instanceof BinaryValue binary) {
            Expression left = lower(binary.left(), context), right = lower(binary.right(), context);
            String operator = binary.operator();
            if ("+".equals(operator) || "-".equals(operator)) {
                Typed a = type(binary.left(), context), b = type(binary.right(), context);
                if (a.kind.date() || b.kind.date()) {
                    if (a.date() && b.date() && "-".equals(operator))
                        return new FunctionValue("days", List.of(left, right));
                    if (a.kind.date())
                        return new FunctionValue(
                                DATE_ADD,
                                List.of(
                                        left,
                                        "+".equals(operator)
                                                ? right
                                                : new BinaryValue(
                                                        "-",
                                                        new NumberValue(BigDecimal.ZERO),
                                                        right)));
                    if ("+".equals(operator))
                        return new FunctionValue(DATE_ADD, List.of(right, left));
                }
            }
            return new BinaryValue(operator, left, right);
        }
        if (expression instanceof FunctionValue call) {
            List<Expression> args = new ArrayList<>();
            for (Expression argument : call.arguments()) args.add(lower(argument, context));
            if ("eomonth".equals(call.name()) && args.size() == 1)
                args.add(new NumberValue(BigDecimal.ZERO));
            return new FunctionValue(call.name(), List.copyOf(args));
        }
        return expression;
    }
}
