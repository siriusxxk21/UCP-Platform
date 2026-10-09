package com.richuang.os.nocode.runtime.dal.query;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.List;

/**
 * 仅由视图服务产生的已授权查询计划，不接收客户端 SQL 片段。grainKeys 只在明细粒度视图承载按明细行统计的下钻时非空：命中的明细行主键（JSON
 * 数组文本），只收窄粒度明细段的横向连接，不影响同一明细段上的汇总列与查找列。
 */
public record DataViewStatement(
        RecordStatement root,
        List<Section> sections,
        List<Column> columns,
        Integer grainSection,
        QueryWrapper<Object> conditions,
        String sortColumn,
        boolean descending,
        int limit,
        int offset,
        String grainKeys) {
    /** 不带统计下钻明细行范围（引入 grainKeys 之前的完整形状）。 */
    public DataViewStatement(
            RecordStatement root,
            List<Section> sections,
            List<Column> columns,
            Integer grainSection,
            QueryWrapper<Object> conditions,
            String sortColumn,
            boolean descending,
            int limit,
            int offset) {
        this(
                root,
                sections,
                columns,
                grainSection,
                conditions,
                sortColumn,
                descending,
                limit,
                offset,
                null);
    }

    public record Section(RecordStatement statement, String correlation, boolean requireMatch) {}

    public record Column(
            String id, int section, String sourceColumn, String kind, boolean numeric) {}
}
