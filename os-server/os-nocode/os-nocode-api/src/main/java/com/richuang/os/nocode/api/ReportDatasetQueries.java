package com.richuang.os.nocode.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** 数据集聚合请求只使用数据集字段身份，不接收源对象、物理列或 SQL。 */
public final class ReportDatasetQueries {
    private ReportDatasetQueries() {}

    /** 候选仅来自有效授权记录；服务端移除自身临时条件，固定条件不可移除。 */
    public record Options(
            String datasetId,
            Integer versionNo,
            String checksum,
            boolean preview,
            String fieldId,
            DataScope filters,
            int pageNo,
            int pageSize,
            String search) {}

    public record Option(String value, String label) {}

    public record OptionPage(List<Option> list, long total, int pageNo, int pageSize) {}

    public record Dimension(String fieldId, String bucket) {}

    public record Metric(
            String id,
            String name,
            String operation,
            String fieldId,
            ApplicationReports.Format format,
            @JsonInclude(JsonInclude.Include.NON_NULL) DataScope conditions,
            @JsonInclude(JsonInclude.Include.NON_NULL) ApplicationReports.Formula formula) {
        public Metric(
                String id,
                String name,
                String operation,
                String fieldId,
                ApplicationReports.Format format) {
            this(id, name, operation, fieldId, format, null, null);
        }

        public Metric(String id, String name, String operation, String fieldId) {
            this(id, name, operation, fieldId, null);
        }
    }

    /** 分组与基础聚合支持等值及结构化筛选；筛选与固定条件、权限取交集，上限 200 组。 */
    public record Query(
            String datasetId,
            Integer versionNo,
            String checksum,
            boolean preview,
            List<Dimension> dimensions,
            List<Metric> metrics,
            Map<String, Object> equal,
            int limit,
            String timeZone,
            List<String> metricIds,
            DataScope filters) {
        /** 兼容既有复用指标调用；未提供筛选时保持原口径。 */
        public Query(
                String datasetId,
                Integer versionNo,
                String checksum,
                boolean preview,
                List<Dimension> dimensions,
                List<Metric> metrics,
                Map<String, Object> equal,
                int limit,
                String timeZone,
                List<String> metricIds) {
            this(
                    datasetId,
                    versionNo,
                    checksum,
                    preview,
                    dimensions,
                    metrics,
                    equal,
                    limit,
                    timeZone,
                    metricIds,
                    null);
        }

        public Query(
                String datasetId,
                Integer versionNo,
                String checksum,
                boolean preview,
                List<Dimension> dimensions,
                List<Metric> metrics,
                Map<String, Object> equal,
                int limit,
                String timeZone) {
            this(
                    datasetId,
                    versionNo,
                    checksum,
                    preview,
                    dimensions,
                    metrics,
                    equal,
                    limit,
                    timeZone,
                    null);
        }
    }
}
