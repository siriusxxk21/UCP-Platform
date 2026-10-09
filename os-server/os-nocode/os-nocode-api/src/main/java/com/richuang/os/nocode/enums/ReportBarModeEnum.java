package com.richuang.os.nocode.enums;

/** 柱状图表现形式，计算值始终保留原始精度。 */
public enum ReportBarModeEnum implements NocodeCodeEnum {
    GROUPED,
    STACKED,
    PERCENT;

    public String getCode() {
        return name();
    }

    public static ReportBarModeEnum fromCode(String value) {
        return NocodeCodeEnum.require(ReportBarModeEnum.class, value);
    }
}
