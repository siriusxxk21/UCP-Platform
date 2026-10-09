package com.lingan.ucp.nocode.enums;

/** 生命周期预检操作，字段动作最终仍通过完整草稿保存及发布生效。 */
public enum ObjectOperationEnum implements NocodeCodeEnum {
    ENABLE("enable"),
    DISABLE("disable"),
    DELETE("delete"),
    DISABLE_FIELD("disable_field"),
    RESTORE_FIELD("restore_field");

    private final String code;

    ObjectOperationEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public boolean fieldOperation() {
        return this == DISABLE_FIELD || this == RESTORE_FIELD;
    }

    public static ObjectOperationEnum fromCode(String code) {
        return NocodeCodeEnum.require(ObjectOperationEnum.class, code);
    }
}
