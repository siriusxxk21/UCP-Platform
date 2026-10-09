package com.lingan.ucp.nocode.enums;

/** 业务记录在当前应用中的打开方式，不接受任意跳转地址。 */
public enum RecordOpenModeEnum implements NocodeCodeEnum {
    DRAWER,
    MODAL;

    public String getCode() {
        return name();
    }

    public static RecordOpenModeEnum fromCode(String code) {
        return NocodeCodeEnum.require(RecordOpenModeEnum.class, code);
    }
}
