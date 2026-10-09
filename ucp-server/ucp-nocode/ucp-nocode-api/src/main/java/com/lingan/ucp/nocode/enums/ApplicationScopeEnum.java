package com.lingan.ucp.nocode.enums;

/** 首版行权限：全部记录或由当前登录人创建的记录。 */
public enum ApplicationScopeEnum implements NocodeCodeEnum {
    ALL("ALL"),
    OWN("OWN");
    private final String code;

    ApplicationScopeEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ApplicationScopeEnum fromCode(String code) {
        return NocodeCodeEnum.require(ApplicationScopeEnum.class, code);
    }
}
