package com.lingan.ucp.nocode.enums;

/** 成员引用已有身份，不创建应用专属账号或身份角色。 */
public enum ApplicationPrincipalEnum implements NocodeCodeEnum {
    USER("USER"),
    ROLE("ROLE");
    private final String code;

    ApplicationPrincipalEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ApplicationPrincipalEnum fromCode(String code) {
        return NocodeCodeEnum.require(ApplicationPrincipalEnum.class, code);
    }
}
