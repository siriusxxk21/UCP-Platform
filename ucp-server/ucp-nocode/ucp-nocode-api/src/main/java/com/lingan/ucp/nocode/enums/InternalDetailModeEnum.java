package com.lingan.ucp.nocode.enums;

/** 内部明细统一画布的运行呈现方式，不改变明细存储或整单提交协议。 */
public enum InternalDetailModeEnum implements NocodeCodeEnum {
    GRID("GRID"),
    CARDS("CARDS");

    private final String code;

    InternalDetailModeEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static InternalDetailModeEnum fromCode(String code) {
        return NocodeCodeEnum.require(InternalDetailModeEnum.class, code);
    }
}
