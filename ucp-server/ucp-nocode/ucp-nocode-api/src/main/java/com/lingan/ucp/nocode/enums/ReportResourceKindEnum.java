package com.lingan.ucp.nocode.enums;

/** 报表资源类别，固定编码持久化。 */
public enum ReportResourceKindEnum implements NocodeCodeEnum {
    FOLDER("FOLDER"),
    DATASET("DATASET"),
    DASHBOARD("DASHBOARD");
    private final String code;

    ReportResourceKindEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
