package com.richuang.os.nocode.enums;

/** 表单标签布局；与底座 Ant Design Vue 的稳定取值一致。 */
public enum FormLayoutEnum implements NocodeCodeEnum {
    VERTICAL("vertical"),
    HORIZONTAL("horizontal");
    private final String code;

    FormLayoutEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static FormLayoutEnum fromCode(String code) {
        return NocodeCodeEnum.require(FormLayoutEnum.class, code);
    }
}
