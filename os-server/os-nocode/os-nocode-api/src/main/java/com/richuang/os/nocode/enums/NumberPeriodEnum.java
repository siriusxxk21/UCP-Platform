package com.richuang.os.nocode.enums;

/** 编号周期同时构成可见日期段及计数分区，避免日/月重置后出现同号。 */
public enum NumberPeriodEnum implements NocodeCodeEnum {
    NONE("NONE"),
    YEAR("YEAR"),
    MONTH("MONTH"),
    DAY("DAY");
    private final String code;

    NumberPeriodEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static NumberPeriodEnum fromCode(String code) {
        return NocodeCodeEnum.require(NumberPeriodEnum.class, code);
    }
}
