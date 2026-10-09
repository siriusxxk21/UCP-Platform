package com.richuang.os.nocode.metadata.service.formula;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.framework.mybatis.core.metadata.PostgreSqlCommands.*;

import java.math.BigDecimal;
import java.util.*;

/** 字段公式的小型声明式语法；不执行脚本、不调用 SQL 函数名称反射。 */
public final class FieldExpressions {
    private final String source;
    private final Map<String, String> columns;
    private int position;
    private int nodes;
    private final Set<String> references = new HashSet<>();

    private FieldExpressions(String source, Map<String, String> columns) {
        this.source = source;
        this.columns = columns;
    }

    public record Parsed(Expression expression, Set<String> references) {}

    public static Parsed parse(String source, Map<String, String> columns) {
        if (source == null || source.isBlank() || source.length() > 1000)
            throw invalid("公式不能为空且最多 1000 字符");
        FieldExpressions parser = new FieldExpressions(source, columns);
        Expression result = parser.comparison(0);
        parser.space();
        if (parser.position != source.length())
            throw invalid("公式包含不支持的内容，位置 " + (parser.position + 1));
        return new Parsed(result, Set.copyOf(parser.references));
    }

    /** 比较优先级低于四则运算；逻辑组合使用 AND/OR/NOT 函数，避免隐式真值转换。 */
    private Expression comparison(int depth) {
        Expression result = sum(depth);
        space();
        for (String operator : List.of(">=", "<=", "!=", "<>", "==", "=", ">", "<")) {
            if (take(operator)) {
                String normalized =
                        "==".equals(operator) ? "=" : "!=".equals(operator) ? "<>" : operator;
                return new BinaryValue(normalized, result, sum(depth));
            }
        }
        return result;
    }

    private Expression sum(int depth) {
        Expression result = product(depth);
        while (true) {
            space();
            if (take("||")) result = new BinaryValue("||", result, product(depth));
            else if (take("+")) result = new BinaryValue("+", result, product(depth));
            else if (take("-")) result = new BinaryValue("-", result, product(depth));
            else return result;
        }
    }

    private Expression product(int depth) {
        Expression result = atom(depth);
        while (true) {
            space();
            if (take("*")) result = new BinaryValue("*", result, atom(depth));
            else if (take("/")) result = new BinaryValue("/", result, atom(depth));
            else return result;
        }
    }

    private Expression atom(int depth) {
        if (depth > 20 || ++nodes > 100) throw invalid("公式过于复杂");
        space();
        if (take("(")) {
            Expression e = comparison(depth + 1);
            space();
            if (!take(")")) throw invalid("公式括号未闭合");
            return e;
        }
        if (take("-")) return new BinaryValue("-", new NumberValue(BigDecimal.ZERO), atom(depth));
        if (take("'")) {
            StringBuilder value = new StringBuilder();
            while (position < source.length()) {
                char c = source.charAt(position++);
                if (c == '\'') {
                    if (take("'")) value.append('\'');
                    else return new TextValue(value.toString());
                } else value.append(c);
            }
            throw invalid("公式文本未闭合");
        }
        // 文字也可以用双引号（钉钉 / Excel 的写法，例如 DATEDIF(a, b, "D")）；原先双引号开头一律报错，不影响存量公式。
        if (take("\"")) {
            StringBuilder value = new StringBuilder();
            while (position < source.length()) {
                char c = source.charAt(position++);
                if (c == '"') {
                    if (take("\"")) value.append('"');
                    else return new TextValue(value.toString());
                } else value.append(c);
            }
            throw invalid("公式文本未闭合");
        }
        if (source.startsWith("[", position))
            throw invalid("字段请直接写字段编码，不带方括号；[字段名] 的写法请在公式编辑器里粘贴，编辑器会自动换成字段编码");
        int start = position;
        while (position < source.length()
                && (Character.isDigit(source.charAt(position)) || source.charAt(position) == '.'))
            position++;
        if (start != position) {
            try {
                BigDecimal number = new BigDecimal(source.substring(start, position));
                if (number.precision() > 38 || number.scale() > 38) throw invalid("公式数字精度最多 38 位");
                return new NumberValue(number);
            } catch (NumberFormatException e) {
                throw invalid("公式数字无效");
            }
        }
        while (position < source.length()
                && (Character.isLetterOrDigit(source.charAt(position))
                        || source.charAt(position) == '_')) position++;
        String name = source.substring(start, position);
        if (name.isEmpty()) throw invalid("公式缺少操作数");
        space();
        if (take("(")) {
            name = name.toLowerCase(Locale.ROOT);
            // 日期函数（YEAR、MONTH、DAYS…）走单独的分支，下面原有的白名单与参数检查一行不动。
            if (FormulaDates.function(name)) return dateFunction(name, depth);
            if (!Set.of(
                            "abs",
                            "round",
                            "lower",
                            "upper",
                            "coalesce",
                            "if",
                            "and",
                            "or",
                            "not",
                            "isblank")
                    .contains(name)) throw invalid("不支持公式函数：" + name);
            List<Expression> args = new ArrayList<>();
            args.add(comparison(depth + 1));
            space();
            while (take(",")) {
                args.add(comparison(depth + 1));
                space();
            }
            if (!take(")") || args.size() > 8) throw invalid("公式函数参数无效");
            if (Set.of("abs", "lower", "upper", "not", "isblank").contains(name) && args.size() != 1
                    || name.equals("round") && args.size() > 2
                    || Set.of("coalesce", "and", "or").contains(name) && args.size() < 2
                    || name.equals("if") && args.size() != 3) throw invalid("公式函数参数数量无效");
            return new FunctionValue(name, args);
        }
        if ("true".equalsIgnoreCase(name)) return new BooleanValue(true);
        if ("false".equalsIgnoreCase(name)) return new BooleanValue(false);
        if ("null".equalsIgnoreCase(name)) return new NullValue();
        if (!columns.containsKey(name)) throw invalid("公式引用字段不存在：" + name);
        references.add(name);
        return new FieldValue(columns.get(name));
    }

    /** 日期函数的参数：TODAY() / NOW() 不带参数，其余按 FormulaDates 登记的个数范围检查。 */
    private Expression dateFunction(String name, int depth) {
        List<Expression> args = new ArrayList<>();
        space();
        if (FormulaDates.noArguments(name)) {
            if (!take(")")) throw invalid(name.toUpperCase(Locale.ROOT) + "() 不带参数");
            return new FunctionValue(name, args);
        }
        args.add(comparison(depth + 1));
        space();
        while (take(",")) {
            args.add(comparison(depth + 1));
            space();
        }
        if (!take(")") || args.size() > 8) throw invalid("公式函数参数无效");
        FormulaDates.checkArity(name, args.size());
        return new FunctionValue(name, args);
    }

    private boolean take(String token) {
        if (source.startsWith(token, position)) {
            position += token.length();
            return true;
        }
        return false;
    }

    private void space() {
        while (position < source.length() && Character.isWhitespace(source.charAt(position)))
            position++;
    }
}
