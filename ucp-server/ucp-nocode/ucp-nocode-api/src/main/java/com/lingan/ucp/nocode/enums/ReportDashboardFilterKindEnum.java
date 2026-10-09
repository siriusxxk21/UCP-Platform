package com.lingan.ucp.nocode.enums;

/** 仪表板公共筛选的受控输入类型。 */
public enum ReportDashboardFilterKindEnum implements NocodeCodeEnum {
    TEXT("TEXT"),
    SELECT("SELECT"),
    MULTISELECT("MULTISELECT"),
    NUMBER_RANGE("NUMBER_RANGE"),
    DATE_RANGE("DATE_RANGE");
    private final String code;

    ReportDashboardFilterKindEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ReportDashboardFilterKindEnum fromCode(String code) {
        return NocodeCodeEnum.require(ReportDashboardFilterKindEnum.class, code);
    }
}
