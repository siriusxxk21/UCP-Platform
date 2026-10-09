package com.richuang.os.nocode.enums;

/**
 * 统计行排序依据：DIMENSION 按行维度的值，METRIC 按排序指标。配置里为空表示沿用引入本项之前的行为 （有排序指标按指标；没有时汇总表与图表按分组值、方向取
 * descending，透视表恒按维度值升序）。
 */
public enum ReportSortByEnum implements NocodeCodeEnum {
    DIMENSION("DIMENSION"),
    METRIC("METRIC");
    private final String code;

    ReportSortByEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ReportSortByEnum fromCode(String code) {
        return NocodeCodeEnum.require(ReportSortByEnum.class, code);
    }
}
