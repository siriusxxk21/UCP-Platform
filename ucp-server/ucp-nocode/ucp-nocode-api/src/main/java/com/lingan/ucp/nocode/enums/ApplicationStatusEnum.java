package com.lingan.ucp.nocode.enums;

/** 应用运行开关；是否已发布由发布版本决定。 */
public enum ApplicationStatusEnum implements NocodeCodeEnum {
    ACTIVE("ACTIVE"),
    DISABLED("DISABLED");
    private final String code;

    ApplicationStatusEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ApplicationStatusEnum fromCode(String code) {
        return NocodeCodeEnum.require(ApplicationStatusEnum.class, code);
    }
}
