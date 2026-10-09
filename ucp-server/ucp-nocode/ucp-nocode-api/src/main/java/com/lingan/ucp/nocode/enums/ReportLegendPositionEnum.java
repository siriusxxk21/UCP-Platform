package com.lingan.ucp.nocode.enums;

/** 图例位置的稳定配置编码。 */
public enum ReportLegendPositionEnum implements NocodeCodeEnum {
    TOP,
    RIGHT,
    BOTTOM,
    LEFT;

    public String getCode() {
        return name();
    }

    public static ReportLegendPositionEnum fromCode(String value) {
        return NocodeCodeEnum.require(ReportLegendPositionEnum.class, value);
    }
}
