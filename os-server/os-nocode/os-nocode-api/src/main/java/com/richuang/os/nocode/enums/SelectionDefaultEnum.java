package com.richuang.os.nocode.enums;

/** 选择字段的稳定配置编码。 */
public enum SelectionDefaultEnum implements NocodeCodeEnum {
    NONE("NONE"),
    FIXED("FIXED"),
    CURRENT_USER_ORGANIZATION("CURRENT_USER_ORGANIZATION");
    private final String code;

    SelectionDefaultEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static SelectionDefaultEnum fromCode(String code) {
        return NocodeCodeEnum.require(SelectionDefaultEnum.class, code);
    }
}
