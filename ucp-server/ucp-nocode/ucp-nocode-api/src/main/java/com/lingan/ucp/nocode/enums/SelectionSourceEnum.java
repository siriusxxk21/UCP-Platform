package com.lingan.ucp.nocode.enums;

/** 选择字段的稳定配置编码。 */
public enum SelectionSourceEnum implements NocodeCodeEnum {
    LOCAL_OPTIONS("LOCAL_OPTIONS"),
    SYSTEM_DICTIONARY("SYSTEM_DICTIONARY"),
    DIRECTORY("DIRECTORY"),
    OBJECT_RELATION("OBJECT_RELATION"),
    /** 挑取值：候选取自另一对象字段已配置的选项定义，不读取业务行。 */
    OBJECT_FIELD_OPTIONS("OBJECT_FIELD_OPTIONS");
    private final String code;

    SelectionSourceEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static SelectionSourceEnum fromCode(String code) {
        return NocodeCodeEnum.require(SelectionSourceEnum.class, code);
    }
}
