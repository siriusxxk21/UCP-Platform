package com.lingan.ucp.framework.mybatis.core.metadata;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * PostgreSQL 结构操作的受控参数工厂。Java 只校验操作、标识符、类型、常量和表达式树； 语句由 PostgreSqlCommandMapper.xml
 * 渲染，沿用调用方事务，不接受外部 SQL。
 */
public final class PostgreSqlCommands {
    private PostgreSqlCommands() {}

    /** 与 XML 分支一一对应的内部操作，不能由请求直接反序列化成可执行命令。 */
    public enum Operation {
        CREATE_TABLE,
        ADD_COLUMN,
        ALTER_TYPE,
        REQUIRED,
        DEFAULT_VALUE,
        DEFAULT_NOW,
        INDEX,
        DROP_INDEX,
        FOREIGN_KEY,
        DROP_CONSTRAINT,
        CHECK,
        COMMENT,
        LOCK,
        WRITE_LOCK,
        STATEMENT_TIMEOUT,
        LOCK_TIMEOUT,
        HAS_ROWS,
        HAS_NULL,
        DUPLICATES,
        ROWS,
        UNLINKED_ROWS
    }

    /** 构造入口封闭；参数在生成命令时完成校验和引用，XML 不接收原始语句。 */
    public static final class Command {
        private final Operation operation;
        private final Map<String, Object> parameters;

        private Command(Operation operation, Map<String, Object> parameters) {
            this.operation = operation;
            this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        }

        public Operation getOperation() {
            return operation;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }
    }

    /** 表达式按访问顺序展开，括号、函数调用和分隔符由 XML 输出。 */
    public enum ExpressionStage {
        VALUE,
        BINARY_START,
        BINARY_OPERATOR,
        END,
        FUNCTION_START,
        SEPARATOR,
        CASE_START,
        CASE_THEN,
        CASE_ELSE,
        CASE_END,
        NOT_START,
        IS_BLANK_START,
        IS_BLANK_END,
        NUMERIC_END
    }

    public record ExpressionToken(ExpressionStage stage, String value) {}

    /** 已校验列定义；审计列的时间默认值和删除检查仍由 XML 明确声明。 */
    public record ColumnDefinition(
            String name,
            String type,
            boolean required,
            String defaultLiteral,
            boolean identity,
            boolean primaryKey,
            List<ExpressionToken> expression,
            boolean baseTime,
            boolean baseDeleted) {}

    public sealed interface Expression
            permits NumberValue,
                    TextValue,
                    BooleanValue,
                    NullValue,
                    FieldValue,
                    BinaryValue,
                    FunctionValue {}

    public record NumberValue(java.math.BigDecimal value) implements Expression {}

    public record TextValue(String value) implements Expression {}

    public record BooleanValue(boolean value) implements Expression {}

    public record NullValue() implements Expression {}

    public record FieldValue(String column) implements Expression {}

    public record BinaryValue(String operator, Expression left, Expression right)
            implements Expression {}

    public record FunctionValue(String name, List<Expression> arguments) implements Expression {}

    public record Column(
            String name,
            String type,
            boolean required,
            String defaultValue,
            boolean identity,
            boolean primaryKey,
            Expression expression) {
        public Column(
                String name,
                String type,
                boolean required,
                String defaultValue,
                boolean identity,
                boolean primaryKey) {
            this(name, type, required, defaultValue, identity, primaryKey, null);
        }
    }

    public static String identifier(String value) {
        if (value == null
                || value.isBlank()
                || value.indexOf('\0') >= 0
                || value.getBytes(StandardCharsets.UTF_8).length > 63) {
            throw new IllegalArgumentException("数据库标识符必填且最多 63 字节");
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    public static String table(String schema, String name) {
        return identifier(schema) + "." + identifier(name);
    }

    private static String literal(String value) {
        if (value == null) return "NULL";
        if (value.indexOf('\0') >= 0 || value.length() > 8000)
            throw new IllegalArgumentException("数据库常量无效");
        // E 字符串显式处理反斜杠，不依赖会话 standard_conforming_strings 设置。
        return "E'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    public static String type(String value) {
        String t = value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
        if (!t.matches(
                "(?:smallint|integer|bigint|boolean|date|text|uuid|jsonb|bytea|time(?: without time"
                    + " zone)?|timestamp(?:\\([0-6]\\))?(?: with(?:out)? time zone)?|character"
                    + " varying\\([1-9][0-9]{0,6}\\)|varchar\\([1-9][0-9]{0,6}\\)|numeric\\([1-9][0-9]?,[0-9]{1,2}\\))")) {
            throw new IllegalArgumentException("不支持生成此数据库类型");
        }
        return t;
    }

    private static List<String> names(List<String> columns) {
        if (columns == null || columns.isEmpty() || columns.size() > 250)
            throw new IllegalArgumentException("字段数量无效");
        return columns.stream().map(PostgreSqlCommands::identifier).toList();
    }

    private static List<ExpressionToken> expression(Expression expression) {
        var tokens = new ArrayList<ExpressionToken>();
        appendExpression(expression, 0, tokens);
        return List.copyOf(tokens);
    }

    private static void appendExpression(
            Expression expression, int depth, List<ExpressionToken> tokens) {
        if (depth > 20) throw new IllegalArgumentException("表达式嵌套过深");
        if (expression instanceof NumberValue n) {
            tokens.add(new ExpressionToken(ExpressionStage.VALUE, n.value().toPlainString()));
        } else if (expression instanceof TextValue t) {
            tokens.add(new ExpressionToken(ExpressionStage.VALUE, literal(t.value())));
        } else if (expression instanceof BooleanValue b) {
            tokens.add(new ExpressionToken(ExpressionStage.VALUE, b.value() ? "TRUE" : "FALSE"));
        } else if (expression instanceof NullValue) {
            tokens.add(new ExpressionToken(ExpressionStage.VALUE, "NULL"));
        } else if (expression instanceof FieldValue f) {
            tokens.add(new ExpressionToken(ExpressionStage.VALUE, identifier(f.column())));
        } else if (expression instanceof BinaryValue b) {
            if (!Set.of("+", "-", "*", "/", "||", "=", "<>", ">", ">=", "<", "<=")
                    .contains(b.operator())) throw new IllegalArgumentException("表达式运算符无效");
            tokens.add(new ExpressionToken(ExpressionStage.BINARY_START, null));
            if ("/".equals(b.operator()))
                tokens.add(new ExpressionToken(ExpressionStage.BINARY_START, null));
            appendExpression(b.left(), depth + 1, tokens);
            if ("/".equals(b.operator()))
                tokens.add(new ExpressionToken(ExpressionStage.NUMERIC_END, null));
            tokens.add(new ExpressionToken(ExpressionStage.BINARY_OPERATOR, b.operator()));
            appendExpression(b.right(), depth + 1, tokens);
            tokens.add(new ExpressionToken(ExpressionStage.END, null));
        } else if (expression instanceof FunctionValue f) {
            // IF 编译为 CASE 保持分支短路；逻辑函数也不能编译为普通 PostgreSQL 函数调用。
            if ("if".equals(f.name()) && f.arguments().size() == 3) {
                tokens.add(new ExpressionToken(ExpressionStage.CASE_START, null));
                appendExpression(f.arguments().get(0), depth + 1, tokens);
                tokens.add(new ExpressionToken(ExpressionStage.CASE_THEN, null));
                appendExpression(f.arguments().get(1), depth + 1, tokens);
                tokens.add(new ExpressionToken(ExpressionStage.CASE_ELSE, null));
                appendExpression(f.arguments().get(2), depth + 1, tokens);
                tokens.add(new ExpressionToken(ExpressionStage.CASE_END, null));
                return;
            }
            if (Set.of("and", "or").contains(f.name())
                    && f.arguments().size() >= 2
                    && f.arguments().size() <= 8) {
                tokens.add(new ExpressionToken(ExpressionStage.BINARY_START, null));
                for (int index = 0; index < f.arguments().size(); index++) {
                    if (index > 0)
                        tokens.add(
                                new ExpressionToken(
                                        ExpressionStage.BINARY_OPERATOR,
                                        f.name().toUpperCase(Locale.ROOT)));
                    appendExpression(f.arguments().get(index), depth + 1, tokens);
                }
                tokens.add(new ExpressionToken(ExpressionStage.END, null));
                return;
            }
            if ("not".equals(f.name()) && f.arguments().size() == 1) {
                tokens.add(new ExpressionToken(ExpressionStage.NOT_START, null));
                appendExpression(f.arguments().getFirst(), depth + 1, tokens);
                tokens.add(new ExpressionToken(ExpressionStage.END, null));
                return;
            }
            if ("isblank".equals(f.name()) && f.arguments().size() == 1) {
                tokens.add(new ExpressionToken(ExpressionStage.IS_BLANK_START, null));
                appendExpression(f.arguments().getFirst(), depth + 1, tokens);
                tokens.add(new ExpressionToken(ExpressionStage.IS_BLANK_END, null));
                return;
            }
            // 日期函数整段渲染成不可变表达式（可用于生成列）；不是日期函数时落到下面原有的白名单。
            if (appendDateFunction(f, depth, tokens)) return;
            if (!Set.of("abs", "round", "lower", "upper", "coalesce").contains(f.name())
                    || f.arguments().isEmpty()
                    || f.arguments().size() > 8) throw new IllegalArgumentException("表达式函数无效");
            tokens.add(new ExpressionToken(ExpressionStage.FUNCTION_START, f.name()));
            boolean first = true;
            for (var argument : f.arguments()) {
                if (!first) tokens.add(new ExpressionToken(ExpressionStage.SEPARATOR, null));
                first = false;
                appendExpression(argument, depth + 1, tokens);
            }
            tokens.add(new ExpressionToken(ExpressionStage.END, null));
        } else throw new IllegalArgumentException("表达式不能为空");
    }

    /**
     * 公式的日期函数。日期时间按日期部分算（先归到 date）；天数、月数、年月日先去掉小数再用。 模板里 $d0 表示把第 0 个参数当日期，$n1 表示把第 1 个参数当整数。用到的
     * EXTRACT、date_trunc、make_date、make_interval、日期加减都是不可变函数。
     */
    private static final Map<String, String> DATE_FUNCTIONS =
            Map.ofEntries(
                    Map.entry("year", "(EXTRACT(YEAR FROM $d0))::integer"),
                    Map.entry("month", "(EXTRACT(MONTH FROM $d0))::integer"),
                    Map.entry("day", "(EXTRACT(DAY FROM $d0))::integer"),
                    Map.entry("weekday:1", "((EXTRACT(ISODOW FROM $d0))::integer % 7 + 1)"),
                    Map.entry("weekday:2", "(EXTRACT(ISODOW FROM $d0))::integer"),
                    Map.entry("weekday:3", "((EXTRACT(ISODOW FROM $d0))::integer - 1)"),
                    Map.entry("days", "($d0 - $d1)"),
                    Map.entry("datedif:D", "($d1 - $d0)"),
                    Map.entry(
                            "datedif:M",
                            "(trunc((((EXTRACT(YEAR FROM $d1) * 12 + EXTRACT(MONTH FROM $d1)) * 32"
                                    + " + EXTRACT(DAY FROM $d1)) - ((EXTRACT(YEAR FROM $d0) * 12"
                                    + " + EXTRACT(MONTH FROM $d0)) * 32 + EXTRACT(DAY FROM $d0)))"
                                    + " / 32))::integer"),
                    Map.entry(
                            "datedif:Y",
                            "(trunc((((EXTRACT(YEAR FROM $d1) * 12 + EXTRACT(MONTH FROM $d1)) * 32"
                                    + " + EXTRACT(DAY FROM $d1)) - ((EXTRACT(YEAR FROM $d0) * 12"
                                    + " + EXTRACT(MONTH FROM $d0)) * 32 + EXTRACT(DAY FROM $d0)))"
                                    + " / 384))::integer"),
                    Map.entry(
                            "eomonth",
                            "((date_trunc('month', ($d0)::timestamp)"
                                    + " + make_interval(months => $n1 + 1))::date - 1)"),
                    Map.entry("edate", "(($d0)::timestamp + make_interval(months => $n1))::date"),
                    Map.entry(
                            "date",
                            "((make_date($n0, 1, 1) + make_interval(months => $n1 - 1))::date"
                                    + " + ($n2 - 1))"),
                    Map.entry("date_add", "($d0 + $n1)"));

    private static final java.util.regex.Pattern DATE_OPERAND =
            java.util.regex.Pattern.compile("\\$([dn])([0-9])");

    private static boolean appendDateFunction(
            FunctionValue f, int depth, List<ExpressionToken> tokens) {
        List<Expression> arguments = f.arguments();
        String key = f.name();
        int count = 1;
        switch (f.name()) {
            case "year", "month", "day" -> {}
            case "weekday" -> {
                // 第二个参数只认常量 1 / 2 / 3（缺省 1），在这里选定算法，不进 SQL。
                key +=
                        ":"
                                + (arguments.size() == 2
                                                && arguments.get(1) instanceof NumberValue n
                                        ? n.value().stripTrailingZeros().toPlainString()
                                        : arguments.size() == 1 ? "1" : "?");
                count = arguments.size();
            }
            case "days", "edate", "eomonth", "date_add" -> count = 2;
            case "datedif" -> {
                key +=
                        ":"
                                + (arguments.size() == 3 && arguments.get(2) instanceof TextValue t
                                        ? t.value().trim().toUpperCase(Locale.ROOT)
                                        : "?");
                count = 3;
            }
            case "date" -> count = 3;
            default -> {
                return false;
            }
        }
        String sql = DATE_FUNCTIONS.get(key);
        if (sql == null || arguments.size() != count)
            throw new IllegalArgumentException("表达式日期函数参数无效");
        var matcher = DATE_OPERAND.matcher(sql);
        int last = 0;
        while (matcher.find()) {
            boolean date = "d".equals(matcher.group(1));
            tokens.add(
                    new ExpressionToken(
                            ExpressionStage.VALUE,
                            sql.substring(last, matcher.start()) + (date ? "(" : "(trunc((")));
            appendExpression(arguments.get(Integer.parseInt(matcher.group(2))), depth + 1, tokens);
            tokens.add(
                    new ExpressionToken(
                            ExpressionStage.VALUE, date ? ")::date" : ")::numeric))::integer"));
            last = matcher.end();
        }
        if (last < sql.length())
            tokens.add(new ExpressionToken(ExpressionStage.VALUE, sql.substring(last)));
        return true;
    }

    private static ColumnDefinition column(Column c) {
        return column(c, false);
    }

    private static ColumnDefinition column(Column c, boolean base) {
        String name = identifier(c.name());
        String nativeType = type(c.type());
        List<ExpressionToken> expression = null;
        String defaultLiteral = null;
        if (c.expression() != null) expression = expression(c.expression());
        else if (c.identity()) {
            if (!Set.of("bigint", "integer", "smallint").contains(type(c.type())))
                throw new IllegalArgumentException("自增列必须为整数");
        } else if (c.defaultValue() != null) {
            defaultLiteral = literal(c.defaultValue());
            type(c.type());
        }
        return new ColumnDefinition(
                name,
                nativeType,
                c.required(),
                defaultLiteral,
                c.identity(),
                c.primaryKey(),
                expression,
                base && c.name().endsWith("_time"),
                base && c.name().equals("deleted"));
    }

    private static Command command(Operation operation, Object... entries) {
        var parameters = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2)
            parameters.put((String) entries[i], entries[i + 1]);
        return new Command(operation, parameters);
    }

    /** 建立显式列定义的表；保留列数及逐列校验顺序。 */
    public static Command createTable(String schema, String name, List<Column> columns) {
        if (columns == null || columns.isEmpty() || columns.size() > 250)
            throw new IllegalArgumentException("建表列数无效");
        String target = table(schema, name);
        return command(
                Operation.CREATE_TABLE,
                "table",
                target,
                "columns",
                columns.stream().map(PostgreSqlCommands::column).toList());
    }

    /** 平台生成表统一使用自增主键和 BaseDO 公共列，设计字段不可覆盖系统列。 */
    public static Command createBusinessTable(String schema, String name, List<Column> fields) {
        if (fields == null || fields.size() > 240) throw new IllegalArgumentException("业务字段数量无效");
        Set<String> used = new HashSet<>(BaseDOColumns.NAMES);
        used.add("id");
        for (Column field : fields)
            if (!used.add(field.name()))
                throw new IllegalArgumentException("字段重复或覆盖底座公共列：" + field.name());
        var columns = new ArrayList<ColumnDefinition>();
        columns.add(column(new Column("id", "bigint", true, null, true, true)));
        for (var field : BaseDOColumns.FIELDS)
            columns.add(
                    column(
                            new Column(
                                    field.name(),
                                    field.type(),
                                    field.required(),
                                    field.defaultValue(),
                                    false,
                                    false),
                            true));
        fields.stream().map(PostgreSqlCommands::column).forEach(columns::add);
        return command(
                Operation.CREATE_TABLE,
                "table",
                table(schema, name),
                "columns",
                List.copyOf(columns));
    }

    /** 纳管只补缺失公共列；历史操作者保持未知，时间记录补齐时间，不覆盖已有值。 */
    public static Command addBaseDOColumn(String schema, String name, String columnName) {
        var field =
                BaseDOColumns.FIELDS.stream()
                        .filter(f -> f.name().equals(columnName))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("只能补齐底座公共列"));
        var definition =
                column(
                        new Column(
                                field.name(),
                                field.type(),
                                field.required(),
                                field.defaultValue(),
                                false,
                                false),
                        true);
        return command(Operation.ADD_COLUMN, "table", table(schema, name), "column", definition);
    }

    /** 关联预检只报告未归属行，不猜测或回填父键。 */
    public static Command unlinkedRows(
            String schema,
            String name,
            String column,
            String parentSchema,
            String parentTable,
            String parentKey,
            boolean required) {
        String child = identifier(column);
        return command(
                Operation.UNLINKED_ROWS,
                "column",
                child,
                "table",
                table(schema, name),
                "parentTable",
                table(parentSchema, parentTable),
                "parentKey",
                identifier(parentKey),
                "required",
                required);
    }

    public static Command addColumn(String schema, String name, Column c) {
        return command(Operation.ADD_COLUMN, "table", table(schema, name), "column", column(c));
    }

    public static Command alterType(String schema, String name, String column, String type) {
        return command(
                Operation.ALTER_TYPE,
                "table",
                table(schema, name),
                "column",
                identifier(column),
                "type",
                type(type));
    }

    /** 空表字段跨类型调整使用已校验的列引用显式转换，避免依赖 PostgreSQL 的隐式转换规则。 */
    public static Command alterTypeUsingCast(
            String schema, String name, String column, String type) {
        return command(
                Operation.ALTER_TYPE,
                "table",
                table(schema, name),
                "column",
                identifier(column),
                "type",
                type(type),
                "usingCast",
                true);
    }

    public static Command required(String schema, String name, String column, boolean required) {
        return command(
                Operation.REQUIRED,
                "table",
                table(schema, name),
                "column",
                identifier(column),
                "required",
                required);
    }

    public static Command defaultValue(
            String schema, String name, String column, String type, String value) {
        return command(
                Operation.DEFAULT_VALUE,
                "table",
                table(schema, name),
                "column",
                identifier(column),
                "value",
                value == null ? null : literal(value),
                "type",
                value == null ? null : type(type));
    }

    public static Command defaultNow(String schema, String name, String column) {
        return command(
                Operation.DEFAULT_NOW, "table", table(schema, name), "column", identifier(column));
    }

    public static Command index(
            String schema, String name, String index, List<String> columns, boolean unique) {
        return command(
                Operation.INDEX,
                "index",
                identifier(index),
                "table",
                table(schema, name),
                "columns",
                names(columns),
                "unique",
                unique);
    }

    public static Command dropIndex(String schema, String index) {
        return command(Operation.DROP_INDEX, "table", table(schema, index));
    }

    public static Command foreignKey(
            String schema,
            String name,
            String constraint,
            String column,
            String targetSchema,
            String targetTable,
            String targetColumn,
            String onDelete) {
        if (!Set.of("CASCADE", "SET_NULL", "RESTRICT").contains(onDelete))
            throw new IllegalArgumentException("删除规则无效");
        return command(
                Operation.FOREIGN_KEY,
                "table",
                table(schema, name),
                "constraint",
                identifier(constraint),
                "column",
                identifier(column),
                "targetTable",
                table(targetSchema, targetTable),
                "targetColumn",
                identifier(targetColumn),
                "onDelete",
                onDelete);
    }

    public static Command dropConstraint(String schema, String name, String constraint) {
        return command(
                Operation.DROP_CONSTRAINT,
                "table",
                table(schema, name),
                "constraint",
                identifier(constraint));
    }

    public static Command check(
            String schema,
            String name,
            String constraint,
            String column,
            String operator,
            String value) {
        if (!Set.of(">=", "<=", "~").contains(operator))
            throw new IllegalArgumentException("校验操作不支持");
        return command(
                Operation.CHECK,
                "table",
                table(schema, name),
                "constraint",
                identifier(constraint),
                "column",
                identifier(column),
                "operator",
                operator,
                "value",
                literal(value));
    }

    public static Command comment(String schema, String name, String column, String value) {
        return command(
                Operation.COMMENT,
                "table",
                table(schema, name),
                "column",
                column == null ? null : identifier(column),
                "value",
                literal(value));
    }

    public static Command lock(String schema, String name) {
        return command(Operation.LOCK, "table", table(schema, name));
    }

    /** 普通写入互相兼容，但与结构发布锁互斥；持锁后再读取最新字段契约。 */
    public static Command writeLock(String schema, String name) {
        return command(Operation.WRITE_LOCK, "table", table(schema, name));
    }

    public static Command statementTimeout() {
        return command(Operation.STATEMENT_TIMEOUT);
    }

    public static Command lockTimeout() {
        return command(Operation.LOCK_TIMEOUT);
    }

    public static Command hasRows(String schema, String name) {
        return command(Operation.HAS_ROWS, "table", table(schema, name));
    }

    public static Command hasNull(String schema, String name, String column) {
        return command(
                Operation.HAS_NULL, "table", table(schema, name), "column", identifier(column));
    }

    public static Command duplicates(String schema, String name, List<String> columns) {
        var present = columns.stream().map(PostgreSqlCommands::identifier).toList();
        return command(
                Operation.DUPLICATES,
                "table",
                table(schema, name),
                "columns",
                names(columns),
                "present",
                present);
    }

    public static Command rows(
            String schema,
            String name,
            List<String> columns,
            List<String> order,
            int limit,
            long offset) {
        return rows(schema, name, columns, order, limit, offset, false);
    }

    /** 仅对已确认遵守 BaseDO 的表过滤逻辑删除行，不能按列名猜测既有表语义。 */
    public static Command rows(
            String schema,
            String name,
            List<String> columns,
            List<String> order,
            int limit,
            long offset,
            boolean logicalDelete) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 100000)
            throw new IllegalArgumentException("预览分页超出范围");
        var sort = order.isEmpty() ? List.<String>of() : names(order);
        return command(
                Operation.ROWS,
                "columns",
                names(columns),
                "table",
                table(schema, name),
                "order",
                sort,
                "limit",
                limit,
                "offset",
                offset,
                "logicalDelete",
                logicalDelete);
    }
}
