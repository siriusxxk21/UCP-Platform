package com.richuang.os.nocode.runtime.dal.query;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.List;
import java.util.Map;

/** 候选复用已授权报表的 FROM/WHERE；搜索在去重后、分页前执行，所有值绑定。 */
public record ReportOptionsStatement(
        RecordStatement base,
        List<ReportStatement.Dimension> dimensions,
        List<ReportStatement.Join> joins,
        List<ReportStatement.Filter> filters,
        String timeZone,
        Map<String, QueryWrapper<Object>> predicates,
        String search,
        List<String> searchKeys,
        ReportStatement.Dimension title,
        List<String> keys,
        int offset,
        int limit,
        List<ReportStatement.Dimension> pathDimensions,
        List<String> path,
        List<ReportStatement.TextFilter> textFilters) {
    public ReportOptionsStatement(
            RecordStatement base,
            List<ReportStatement.Dimension> dimensions,
            List<ReportStatement.Join> joins,
            List<ReportStatement.Filter> filters,
            String timeZone,
            Map<String, QueryWrapper<Object>> predicates,
            String search,
            List<String> searchKeys,
            ReportStatement.Dimension title,
            List<String> keys,
            int offset,
            int limit) {
        this(
                base,
                dimensions,
                joins,
                filters,
                timeZone,
                predicates,
                search,
                searchKeys,
                title,
                keys,
                offset,
                limit,
                List.of(),
                List.of(),
                List.of());
    }

    public ReportOptionsStatement(
            ReportStatement statement,
            ReportStatement.Dimension dimension,
            ReportStatement.Dimension title,
            List<String> keys,
            String search,
            List<String> searchKeys,
            int offset,
            int limit) {
        this(
                statement.base(),
                List.of(dimension),
                statement.joins(),
                statement.filters(),
                statement.timeZone(),
                statement.predicates(),
                search,
                searchKeys,
                title,
                keys,
                offset,
                limit,
                statement.pathDimensions(),
                statement.path(),
                statement.textFilters());
    }

    /** 数据集候选按根记录取值，不采用应用统计的明细粒度。 */
    public ReportStatement.Detail detail() {
        return null;
    }

    public String dateFrom() {
        return null;
    }

    public String dateToExclusive() {
        return null;
    }
}
