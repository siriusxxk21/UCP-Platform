package com.lingan.ucp.nocode.enums;

/** 透视表占比基准：所在行合计、所在列合计或总计；NONE 不计算占比。 */
public enum ReportPivotPercentEnum implements NocodeCodeEnum {
    NONE("NONE"),
    ROW("ROW"),
    COLUMN("COLUMN"),
    TOTAL("TOTAL");
    private final String code;

    ReportPivotPercentEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ReportPivotPercentEnum fromCode(String code) {
        return NocodeCodeEnum.require(ReportPivotPercentEnum.class, code);
    }
}
