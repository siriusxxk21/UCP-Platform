package com.lingan.ucp.nocode.metadata.service.formula;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommands.*;

import java.math.*;
import java.util.*;
import java.util.function.Function;

/** 复用已解析的白名单表达式；十进制运算不经过浮点数，空值与除零显式处理。 */
public final class FormulaEvaluator {
    private FormulaEvaluator() {}

    public static Object evaluate(Expression expression, Function<String, Object> fields) {
        if (expression instanceof FieldValue v) return fields.apply(v.column());
        if (expression instanceof NumberValue v) return v.value();
        if (expression instanceof TextValue v) return v.value();
        if (expression instanceof BooleanValue v) return v.value();
        if (expression instanceof NullValue) return null;
        if (expression instanceof BinaryValue v) {
            Object left = evaluate(v.left(), fields), right = evaluate(v.right(), fields);
            if (left == null || right == null) return null;
            if ("||".equals(v.operator())) return left.toString() + right;
            if (Set.of("=", "<>", ">", ">=", "<", "<=").contains(v.operator())) {
                int order = compare(left, right);
                return switch (v.operator()) {
                    case "=" -> order == 0;
                    case "<>" -> order != 0;
                    case ">" -> order > 0;
                    case ">=" -> order >= 0;
                    case "<" -> order < 0;
                    default -> order <= 0;
                };
            }
            // 「+」「−」两边有日期时按日期加减算（日期 − 日期 = 天数，日期 ± 天数 = 日期）；原先这种写法一律报「须为数值」。
            if (FormulaDates.dateArithmetic(v.operator(), left, right))
                return FormulaDates.arithmetic(v.operator(), left, right);
            BigDecimal a = number(left);
            BigDecimal b = number(right);
            return switch (v.operator()) {
                case "+" -> a.add(b);
                case "-" -> a.subtract(b);
                case "*" -> a.multiply(b);
                case "/" -> {
                    if (b.signum() == 0) throw invalid("公式除数不能为零");
                    yield a.divide(b, 16, RoundingMode.HALF_UP);
                }
                default -> throw invalid("不支持的公式算子");
            };
        }
        if (expression instanceof FunctionValue v) {
            if (FormulaDates.function(v.name())) return FormulaDates.evaluate(v, fields);
            if ("if".equals(v.name())) {
                Boolean condition = bool(evaluate(v.arguments().getFirst(), fields));
                return evaluate(v.arguments().get(Boolean.TRUE.equals(condition) ? 1 : 2), fields);
            }
            if (Set.of("and", "or").contains(v.name())) {
                boolean and = "and".equals(v.name());
                boolean unknown = false;
                for (Expression argument : v.arguments()) {
                    Boolean operand = bool(evaluate(argument, fields));
                    if (operand == null) unknown = true;
                    else if (operand != and) return operand;
                }
                return unknown ? null : and;
            }
            if ("coalesce".equals(v.name())) {
                for (Expression arg : v.arguments()) {
                    Object value = evaluate(arg, fields);
                    if (value != null) return value;
                }
                return null;
            }
            Object first = evaluate(v.arguments().getFirst(), fields);
            if ("isblank".equals(v.name())) return first == null || "".equals(first);
            if (first == null) return null;
            return switch (v.name()) {
                case "not" -> !bool(first);
                case "abs" -> number(first).abs();
                case "lower" -> first.toString().toLowerCase(Locale.ROOT);
                case "upper" -> first.toString().toUpperCase(Locale.ROOT);
                case "round" -> {
                    int scale =
                            v.arguments().size() == 1
                                    ? 0
                                    : number(evaluate(v.arguments().get(1), fields))
                                            .intValueExact();
                    if (scale < -10 || scale > 10) throw invalid("round 精度范围为 -10 到 10");
                    yield number(first).setScale(scale, RoundingMode.HALF_UP);
                }
                default -> throw invalid("不支持的公式函数");
            };
        }
        throw invalid("公式表达式无效");
    }

    /** 条件只接受布尔与空值，不把任意数字或文本悄悄转换为真假。 */
    public static Boolean bool(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean booleanValue) return booleanValue;
        throw invalid("公式条件须为布尔值，请使用比较运算或 ISBLANK");
    }

    /** 数值字段由调用方保留为 BigDecimal；文本编码例如 001 不会丢失前导零。 */
    public static int compare(Object left, Object right) {
        if (left instanceof Number || right instanceof Number)
            return number(left).compareTo(number(right));
        if (left instanceof Boolean || right instanceof Boolean)
            return Boolean.compare(bool(left), bool(right));
        return left.toString().compareTo(right.toString());
    }

    public static BigDecimal number(Object value) {
        try {
            return new BigDecimal(value.toString());
        } catch (RuntimeException e) {
            throw invalid("公式操作数须为数值");
        }
    }
}
