package com.lingan.ucp.nocode.enums;

/** 受控动作类型，不接受任意代码或 SQL。 */
public enum BusinessActionKindEnum implements NocodeCodeEnum {
    UPDATE_FIELDS("UPDATE_FIELDS"),
    CAPTURE_VALUES("CAPTURE_VALUES"),
    START_PROCESS("START_PROCESS");
    private final String code;

    BusinessActionKindEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static BusinessActionKindEnum fromCode(String code) {
        return NocodeCodeEnum.require(BusinessActionKindEnum.class, code);
    }
}
