package com.richuang.os.nocode.enums;

/** 统计视图的封闭计算集合，不扩展对象汇总字段的既有 DSL。 */
public enum ReportOperationEnum implements NocodeCodeEnum {
    COUNT,
    COUNT_FIELD,
    COUNT_DISTINCT,
    /** 只用于按明细行统计：去重计数所属主记录，不带字段。 */
    COUNT_ROOT,
    SUM,
    AVG,
    MIN,
    MAX,
    FORMULA;

    public String getCode() {
        return name();
    }

    public static ReportOperationEnum fromCode(String value) {
        return NocodeCodeEnum.require(ReportOperationEnum.class, value);
    }

    public boolean zeroWhenEmpty() {
        return this == COUNT
                || this == COUNT_FIELD
                || this == COUNT_DISTINCT
                || this == COUNT_ROOT
                || this == SUM;
    }
}
