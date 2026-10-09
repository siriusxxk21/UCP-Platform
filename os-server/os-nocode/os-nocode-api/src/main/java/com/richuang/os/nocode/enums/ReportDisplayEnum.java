package com.richuang.os.nocode.enums;

/** 统计视图展示类型。 */
public enum ReportDisplayEnum implements NocodeCodeEnum {
    METRIC("METRIC"),
    BAR("BAR"),
    LINE("LINE"),
    PIE("PIE"),
    TABLE("TABLE"),
    /** 透视表：行维度 × 列维度（列组），小计与合计由后端按原始记录重新聚合。 */
    PIVOT("PIVOT");
    private final String code;

    ReportDisplayEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ReportDisplayEnum fromCode(String code) {
        return NocodeCodeEnum.require(ReportDisplayEnum.class, code);
    }
}
