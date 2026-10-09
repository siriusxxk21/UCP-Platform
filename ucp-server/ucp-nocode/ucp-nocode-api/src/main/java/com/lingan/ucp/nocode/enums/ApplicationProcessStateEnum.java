package com.lingan.ucp.nocode.enums;

/** 业务关联状态，终态由底座 BPM 状态事件驱动。 */
public enum ApplicationProcessStateEnum implements NocodeCodeEnum {
    RUNNING("RUNNING"),
    APPROVED("APPROVED"),
    REJECTED("REJECTED"),
    CANCELED("CANCELED");
    private final String code;

    ApplicationProcessStateEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ApplicationProcessStateEnum fromCode(String code) {
        return NocodeCodeEnum.require(ApplicationProcessStateEnum.class, code);
    }
}
