package com.richuang.os.nocode.enums;

import java.util.Set;

/** 原地字段转换的值处理动作；编码持久化在发布计划中，不能根据受影响数量推断是否清空。 */
public enum FieldConversionActionEnum implements NocodeCodeEnum {
    CLEAR_COLUMN("CLEAR_COLUMN"),
    PRESERVE_VALUES("PRESERVE_VALUES"),
    KEEP_COLUMN("KEEP_COLUMN");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(FieldConversionActionEnum.class);

    FieldConversionActionEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static FieldConversionActionEnum fromCode(String code) {
        return NocodeCodeEnum.require(FieldConversionActionEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
