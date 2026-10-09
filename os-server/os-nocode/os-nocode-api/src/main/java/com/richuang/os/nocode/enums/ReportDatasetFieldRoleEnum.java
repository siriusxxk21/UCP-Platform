package com.richuang.os.nocode.enums;

/** 分析数据集字段用途；字段实际类型由固定对象版本推导。 */
public enum ReportDatasetFieldRoleEnum implements NocodeCodeEnum {
    DIMENSION("DIMENSION"),
    MEASURE("MEASURE");

    private final String code;

    ReportDatasetFieldRoleEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ReportDatasetFieldRoleEnum fromCode(String code) {
        return NocodeCodeEnum.require(ReportDatasetFieldRoleEnum.class, code);
    }
}
