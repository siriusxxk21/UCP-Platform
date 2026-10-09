package com.lingan.ucp.nocode.runtime.dal.query;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.List;
import java.util.Map;

/** 明细投影复用统计 FROM 与 drill，字段仅来自已授权固定数据集。 */
public record ReportDetailStatement(
        ReportStatement statement, List<ReportStatement.Dimension> fields, int offset, int limit) {
    public RecordStatement base() {
        return statement.base();
    }

    public List<ReportStatement.Join> joins() {
        return statement.joins();
    }

    public List<ReportStatement.Filter> filters() {
        return statement.filters();
    }

    public Map<String, QueryWrapper<Object>> predicates() {
        return statement.predicates();
    }

    public List<ReportStatement.Dimension> dimensions() {
        return statement.dimensions();
    }

    public List<ReportStatement.Dimension> columnDimensions() {
        return statement.columnDimensions();
    }

    public List<String> group() {
        return statement.group();
    }

    public List<String> columnGroup() {
        return statement.columnGroup();
    }

    public Integer drillMetricIndex() {
        return statement.drillMetricIndex();
    }

    public List<String> metricColumns() {
        return statement.metricColumns();
    }

    /** 与公共 FROM/drill 片段保持完整参数契约。 */
    public ReportStatement.Detail detail() {
        return statement.detail();
    }

    public List<String> metricAliases() {
        return statement.metricAliases();
    }

    public String timeZone() {
        return statement.timeZone();
    }

    public List<ReportStatement.Dimension> pathDimensions() {
        return statement.pathDimensions();
    }

    public List<String> path() {
        return statement.path();
    }

    public List<ReportStatement.TextFilter> textFilters() {
        return statement.textFilters();
    }

    public String dateFrom() {
        return null;
    }

    public String dateToExclusive() {
        return null;
    }
}
