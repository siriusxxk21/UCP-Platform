package com.richuang.os.nocode.enums;

import java.util.Set;

/** 字段显示值来源，复用底座人员和组织能力。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum DisplayResolverEnum implements NocodeCodeEnum {
    NONE("NONE"),
    LOCAL_OPTIONS("LOCAL_OPTIONS"),
    RECORD_TITLE("RECORD_TITLE"),
    USER("USER"),
    DEPARTMENT("DEPARTMENT");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(DisplayResolverEnum.class);

    DisplayResolverEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static DisplayResolverEnum fromCode(String code) {
        return NocodeCodeEnum.require(DisplayResolverEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
