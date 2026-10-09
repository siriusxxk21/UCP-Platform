package com.richuang.os.nocode.api;

import com.richuang.os.nocode.api.DataCenter.FieldOptions;
import com.richuang.os.nocode.enums.FieldTypeEnum;

import java.util.Locale;
import java.util.regex.Pattern;

/** 字段物理类型的共同契约，供结构编译、发布检查和业务运行复用。 */
public final class FieldStorage {
    private FieldStorage() {}

    public static String sqlType(FieldDefinition field, FieldOptions options) {
        if (options.nativeType() != null) return options.nativeType();
        return switch (FieldTypeEnum.fromCode(field.type())) {
            case TEXT -> "varchar(" + field.length() + ")";
            case TEXTAREA, RICH_TEXT -> "text";
            case INTEGER, ORGANIZATION, USER, DEPARTMENT, POST, USER_GROUP, REFERENCE -> "bigint";
            case AUTO_NUMBER -> options.autoNumber() == null ? "bigint" : "text";
            case DECIMAL, MONEY, PERCENT ->
                    "numeric(" + field.precision() + "," + field.scale() + ")";
            case BOOLEAN -> "boolean";
            case DATE -> "date";
            case DATETIME -> "timestamp without time zone";
            case TIME -> "time without time zone";
            case UUID -> "uuid";
            case SELECT -> "varchar(100)";
            case MULTI_SELECT, IMAGE, ATTACHMENT, REGION, CASCADE, URL -> "jsonb";
            case FORMULA, SUMMARY ->
                    switch (FieldTypeEnum.fromCode(options.resultType())) {
                        case TEXT -> "text";
                        case INTEGER -> "bigint";
                        case DECIMAL, MONEY -> "numeric(38,10)";
                        default -> throw new IllegalArgumentException("计算结果类型无效");
                    };
        };
    }

    /** 同类型扩宽可继续承载旧字段；类型替换或精度缩小不能由旧表单静默承担。 */
    public static boolean accepts(String expected, String actual) {
        String old = canonical(expected), current = canonical(actual);
        if (old.equals(current)) return true;
        var text = Pattern.compile("varchar\\((\\d+)\\)").matcher(old);
        if (text.matches()) {
            if (current.equals("text") || current.equals("varchar")) return true;
            var next = Pattern.compile("varchar\\((\\d+)\\)").matcher(current);
            return next.matches()
                    && Integer.parseInt(next.group(1)) >= Integer.parseInt(text.group(1));
        }
        var number = Pattern.compile("numeric\\((\\d+),(\\d+)\\)").matcher(old);
        if (number.matches()) {
            if (current.equals("numeric")) return true;
            var next = Pattern.compile("numeric\\((\\d+),(\\d+)\\)").matcher(current);
            return next.matches()
                    && next.group(2).equals(number.group(2))
                    && Integer.parseInt(next.group(1)) >= Integer.parseInt(number.group(1));
        }
        return false;
    }

    private static String canonical(String type) {
        return type.toLowerCase(Locale.ROOT)
                .trim()
                .replace("character varying", "varchar")
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("\\s+", " ");
    }
}
