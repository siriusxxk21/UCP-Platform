package com.richuang.os.nocode.enums;

/** 多行结果必须明确聚合；SINGLE 超过一行报错。 */
public enum CalculationAggregateEnum implements NocodeCodeEnum {
    SINGLE("SINGLE"),
    COUNT("COUNT"),
    SUM("SUM"),
    AVG("AVG"),
    MIN("MIN"),
    MAX("MAX");
    private final String code;

    CalculationAggregateEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static CalculationAggregateEnum fromCode(String code) {
        return NocodeCodeEnum.require(CalculationAggregateEnum.class, code);
    }
}
