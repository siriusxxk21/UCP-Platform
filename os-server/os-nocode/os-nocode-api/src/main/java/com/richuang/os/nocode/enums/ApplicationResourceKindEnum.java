package com.richuang.os.nocode.enums;

/** 应用内资源类型；枚举存在不表示该类型已完成运行接入。 */
public enum ApplicationResourceKindEnum implements NocodeCodeEnum {
    VIEW("VIEW"),
    REPORT("REPORT"),
    REPORT_DASHBOARD("REPORT_DASHBOARD"),
    FORM("FORM"),
    PAGE("PAGE"),
    MENU("MENU"),
    DICTIONARY("DICTIONARY"),
    NUMBER_RULE("NUMBER_RULE"),
    TASK_ENTRY("TASK_ENTRY"),
    ACTION("ACTION"),
    AUTOMATION("AUTOMATION");
    private final String code;

    ApplicationResourceKindEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ApplicationResourceKindEnum fromCode(String code) {
        return NocodeCodeEnum.require(ApplicationResourceKindEnum.class, code);
    }
}
