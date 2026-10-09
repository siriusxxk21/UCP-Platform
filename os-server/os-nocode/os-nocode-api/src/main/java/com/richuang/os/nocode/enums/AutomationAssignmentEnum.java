package com.richuang.os.nocode.enums;

/** 固定值和来源字段用于事件；其余方式对完整有效来源集合重算。 */
public enum AutomationAssignmentEnum implements NocodeCodeEnum {
    VALUE,
    FIELD,
    COUNT,
    SUM,
    MIN,
    MAX,
    EXISTS;

    public String getCode() {
        return name();
    }

    public static AutomationAssignmentEnum fromCode(String code) {
        return NocodeCodeEnum.require(AutomationAssignmentEnum.class, code);
    }
}
