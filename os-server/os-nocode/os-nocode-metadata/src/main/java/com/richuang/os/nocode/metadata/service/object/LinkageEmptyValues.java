package com.richuang.os.nocode.metadata.service.object;

import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.enums.FieldTypeEnum;

import java.math.BigInteger;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据联动「没有匹配记录时填入」的字面量口径：配置里存字符串（与 defaultValue 同一种做法），按目标字段类型解析。 发布校验与运行期求值共用这一份，不另写第二份。
 *
 * <p>一期只支持文本、多行文本、单选、整数、小数、百分比、金额、布尔。金额按日元整数保存：带小数直接拒绝，不取整、不四舍五入
 * （取整档位只管求值结果，不改用户填的字面量）。单选存选项编码；编码是否为有效选项由发布校验按生效选项集另行核对。
 */
public final class LinkageEmptyValues {
    /** 没设长度的文本字段按这个上限校验字面量。 */
    public static final int DEFAULT_TEXT_LIMIT = 4000;

    /** 只认 ASCII 数字的十进制写法：不接受全角数字、指数写法与多余符号。 */
    private static final Pattern INTEGER_TEXT = Pattern.compile("[+-]?[0-9]+");

    private static final Pattern DECIMAL_TEXT = Pattern.compile("[+-]?[0-9]+(\\.[0-9]+)?");

    private static final Set<FieldTypeEnum> SUPPORTED =
            Set.of(
                    FieldTypeEnum.TEXT,
                    FieldTypeEnum.TEXTAREA,
                    FieldTypeEnum.SELECT,
                    FieldTypeEnum.INTEGER,
                    FieldTypeEnum.DECIMAL,
                    FieldTypeEnum.PERCENT,
                    FieldTypeEnum.MONEY,
                    FieldTypeEnum.BOOLEAN);

    /** 字面量不符合目标类型的原因；调用方据此选报错文案。 */
    public enum Problem {
        UNSUPPORTED_TYPE,
        MONEY_NOT_INTEGER,
        TEXT_TOO_LONG,
        MALFORMED
    }

    private LinkageEmptyValues() {}

    public static boolean supported(FieldDefinition target) {
        return FieldTypeEnum.containsCode(target.type())
                && SUPPORTED.contains(FieldTypeEnum.fromCode(target.type()));
    }

    /** 文本字面量的长度上限：字段长度，没设按 {@link #DEFAULT_TEXT_LIMIT}。 */
    public static int textLimit(FieldDefinition target) {
        return target.length() == null ? DEFAULT_TEXT_LIMIT : target.length();
    }

    /** 字面量是否符合目标类型；符合返回 null。不核对单选编码是否为有效选项。 */
    public static Problem check(FieldDefinition target, String literal) {
        if (!supported(target)) return Problem.UNSUPPORTED_TYPE;
        try {
            switch (FieldTypeEnum.fromCode(target.type())) {
                case TEXT, TEXTAREA -> {
                    if (literal.length() > textLimit(target)) return Problem.TEXT_TOO_LONG;
                }
                case INTEGER -> {
                    if (!INTEGER_TEXT.matcher(literal).matches()) return Problem.MALFORMED;
                    new BigInteger(literal).longValueExact();
                }
                case DECIMAL, PERCENT -> {
                    if (!DECIMAL_TEXT.matcher(literal).matches()) return Problem.MALFORMED;
                    FieldDefaultValidation.requireDecimal(target, literal);
                }
                case MONEY -> {
                    // 先确认是数，再确认是整数：「12.5」报「请填写整数」，「abc」报「不符合字段类型」。
                    if (!DECIMAL_TEXT.matcher(literal).matches()) return Problem.MALFORMED;
                    if (!INTEGER_TEXT.matcher(literal).matches()) return Problem.MONEY_NOT_INTEGER;
                    FieldDefaultValidation.requireDecimal(target, literal);
                }
                case BOOLEAN -> {
                    if (!Set.of("true", "false").contains(literal)) return Problem.MALFORMED;
                }
                default -> {
                    // SELECT：任意非空编码，有效性由发布校验按选项集核对。
                }
            }
        } catch (IllegalArgumentException | ArithmeticException e) {
            return Problem.MALFORMED;
        }
        return null;
    }

    /** 解析成写入值：布尔为 Boolean，其余保持字面量字符串（记录写入层按字段类型再转换）。字面量不符合目标类型时抛 IllegalArgumentException。 */
    public static Object parse(FieldDefinition target, String literal) {
        var problem = check(target, literal);
        if (problem != null)
            throw new IllegalArgumentException("emptyValue does not fit target field: " + problem);
        return FieldTypeEnum.BOOLEAN.matches(target.type()) ? Boolean.valueOf(literal) : literal;
    }
}
