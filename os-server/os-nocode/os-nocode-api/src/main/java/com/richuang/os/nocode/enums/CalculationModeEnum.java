package com.richuang.os.nocode.enums;

/** 计算来源：本行、关系、条件匹配、全表统计与固定业务顺序累计。 */
public enum CalculationModeEnum implements NocodeCodeEnum {
    LOCAL("LOCAL"),
    RELATION("RELATION"),
    LOOKUP("LOOKUP"),
    STATISTICS("STATISTICS"),
    RUNNING_TOTAL("RUNNING_TOTAL"),
    SEQUENCE("SEQUENCE");
    private final String code;

    CalculationModeEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static CalculationModeEnum fromCode(String code) {
        return NocodeCodeEnum.require(CalculationModeEnum.class, code);
    }
}
