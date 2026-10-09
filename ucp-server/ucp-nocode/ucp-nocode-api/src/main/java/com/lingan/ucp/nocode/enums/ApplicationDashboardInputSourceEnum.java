package com.lingan.ucp.nocode.enums;

/** 看板输入来源为声明参数或已授权当前记录，不能配置任意表达式。 */
public enum ApplicationDashboardInputSourceEnum implements NocodeCodeEnum {
    PARAMETER("PARAMETER"),
    RECORD_ID("RECORD_ID"),
    RECORD_FIELD("RECORD_FIELD");

    private final String code;

    ApplicationDashboardInputSourceEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ApplicationDashboardInputSourceEnum fromCode(String code) {
        return NocodeCodeEnum.require(ApplicationDashboardInputSourceEnum.class, code);
    }
}
