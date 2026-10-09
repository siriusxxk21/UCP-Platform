package com.lingan.ucp.nocode.enums;

/** 资源管理与复用权限；USE/VIEW/EXPORT 本身不授予业务数据访问权。 */
public enum ReportResourceActionEnum implements NocodeCodeEnum {
    VIEW_META("VIEW_META"),
    EDIT("EDIT"),
    PUBLISH("PUBLISH"),
    USE("USE"),
    AUTHORIZE_DATA("AUTHORIZE_DATA"),
    GRANT("GRANT"),
    DELETE("DELETE"),
    VIEW("VIEW"),
    EXPORT("EXPORT");
    private final String code;

    ReportResourceActionEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ReportResourceActionEnum fromCode(String code) {
        return NocodeCodeEnum.require(ReportResourceActionEnum.class, code);
    }
}
