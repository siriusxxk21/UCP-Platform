package com.richuang.os.nocode.enums;

/** 业务操作权限，区别于应用设计和发布权限。 */
public enum ApplicationActionEnum implements NocodeCodeEnum {
    READ("READ"),
    CREATE("CREATE"),
    UPDATE("UPDATE"),
    DELETE("DELETE"),
    IMPORT("IMPORT"),
    EXPORT("EXPORT"),
    START_PROCESS("START_PROCESS");
    private final String code;

    ApplicationActionEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ApplicationActionEnum fromCode(String code) {
        return NocodeCodeEnum.require(ApplicationActionEnum.class, code);
    }
}
