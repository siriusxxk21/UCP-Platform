package com.richuang.os.nocode.enums;

/** 选择字段的稳定配置编码。 */
public enum SelectionAppearanceEnum implements NocodeCodeEnum {
    AUTO("AUTO"),
    SELECT("SELECT"),
    TREE("TREE"),
    MODAL("MODAL");
    private final String code;

    SelectionAppearanceEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static SelectionAppearanceEnum fromCode(String code) {
        return NocodeCodeEnum.require(SelectionAppearanceEnum.class, code);
    }
}
