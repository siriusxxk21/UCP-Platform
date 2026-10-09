package com.lingan.ucp.nocode.enums;

/** 整单操作的稳定持久化编码。 */
public enum DocumentOperationEnum implements NocodeCodeEnum {
    SAVE("SAVE");
    private final String code;

    DocumentOperationEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }
}
