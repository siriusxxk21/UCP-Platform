package com.richuang.os.nocode.enums;

/** 统计分组粒度；日期分桶由服务端明确时区。 */
public enum ReportBucketEnum implements NocodeCodeEnum {
    VALUE("VALUE"),
    DAY("DAY"),
    MONTH("MONTH"),
    YEAR("YEAR");
    private final String code;

    ReportBucketEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ReportBucketEnum fromCode(String code) {
        return NocodeCodeEnum.require(ReportBucketEnum.class, code);
    }
}
