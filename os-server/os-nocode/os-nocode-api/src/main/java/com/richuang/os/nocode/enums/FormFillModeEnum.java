package com.richuang.os.nocode.enums;

/** 关联带入保存为本记录快照；持续实时取值沿用对象计算字段。 */
public enum FormFillModeEnum implements NocodeCodeEnum {
    DEFAULT("DEFAULT"),
    SOURCE_CHANGE("SOURCE_CHANGE");
    private final String code;

    FormFillModeEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static FormFillModeEnum fromCode(String code) {
        return NocodeCodeEnum.require(FormFillModeEnum.class, code);
    }
}
