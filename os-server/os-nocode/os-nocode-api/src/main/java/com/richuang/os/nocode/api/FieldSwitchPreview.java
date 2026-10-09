package com.richuang.os.nocode.api;

import java.util.List;

/** 字段切换只读预检；统计未知时返回 null，不把未部署或缺失列当成空列。 */
public final class FieldSwitchPreview {
    private FieldSwitchPreview() {}

    public enum DeploymentState {
        DEPLOYED,
        UNPUBLISHED,
        MISSING_COLUMN
    }

    public enum Decision {
        PRESERVE,
        CLEAR_COLUMN,
        BLOCKED,
        UNPUBLISHED
    }

    /** 各原因的计数可以重叠；failedRows 为这些原因涉及记录的去重并集。 */
    public record Conflict(String code, long count, String message) {}

    /** 实际属性来自数据库目录；目标属性来自预检定义，DDL 仅在最终发布事务内执行。 */
    public record Storage(
            String schemaName,
            String tableName,
            String columnName,
            String actualType,
            String targetType,
            Boolean nullable,
            String defaultExpression,
            Boolean primaryKey,
            Boolean generated,
            Boolean ddlRequired,
            String ddlExplanation) {}

    public record Request(
            String objectId,
            String detailId,
            String fieldId,
            String targetType,
            Integer length,
            Integer precision,
            Integer scale,
            SelectionFields.Source selection,
            String targetObjectId,
            Boolean detachRelation,
            Boolean targetRequired,
            Boolean targetUnique,
            String targetMinimum,
            String targetMaximum,
            String targetPattern,
            String targetDefaultValue,
            List<DataCenter.Option> targetOptions) {
        public Request(
                String objectId,
                String detailId,
                String fieldId,
                String targetType,
                Integer length,
                Integer precision,
                Integer scale,
                SelectionFields.Source selection,
                String targetObjectId,
                Boolean detachRelation,
                Boolean targetRequired,
                Boolean targetUnique,
                String targetMinimum,
                String targetMaximum,
                String targetPattern,
                String targetDefaultValue) {
            this(
                    objectId,
                    detailId,
                    fieldId,
                    targetType,
                    length,
                    precision,
                    scale,
                    selection,
                    targetObjectId,
                    detachRelation,
                    targetRequired,
                    targetUnique,
                    targetMinimum,
                    targetMaximum,
                    targetPattern,
                    targetDefaultValue,
                    null);
        }

        public Request(
                String objectId,
                String detailId,
                String fieldId,
                String targetType,
                Integer length,
                Integer precision,
                Integer scale,
                SelectionFields.Source selection,
                String targetObjectId,
                Boolean detachRelation,
                Boolean targetRequired,
                Boolean targetUnique) {
            this(
                    objectId,
                    detailId,
                    fieldId,
                    targetType,
                    length,
                    precision,
                    scale,
                    selection,
                    targetObjectId,
                    detachRelation,
                    targetRequired,
                    targetUnique,
                    null,
                    null,
                    null,
                    null);
        }

        public Request(
                String objectId,
                String detailId,
                String fieldId,
                String targetType,
                Integer length,
                Integer precision,
                Integer scale,
                SelectionFields.Source selection,
                String targetObjectId,
                Boolean detachRelation) {
            this(
                    objectId,
                    detailId,
                    fieldId,
                    targetType,
                    length,
                    precision,
                    scale,
                    selection,
                    targetObjectId,
                    detachRelation,
                    null,
                    null);
        }
    }

    /** 与切换预检使用同一候选配置，另带展示分页；只读取已发布列的旧值。 */
    public record RowsRequest(
            String objectId,
            String detailId,
            String fieldId,
            String targetType,
            Integer length,
            Integer precision,
            Integer scale,
            SelectionFields.Source selection,
            String targetObjectId,
            Boolean detachRelation,
            Boolean targetRequired,
            Boolean targetUnique,
            int pageNo,
            int pageSize,
            String targetMinimum,
            String targetMaximum,
            String targetPattern,
            String targetDefaultValue,
            List<DataCenter.Option> targetOptions) {
        public RowsRequest(
                String objectId,
                String detailId,
                String fieldId,
                String targetType,
                Integer length,
                Integer precision,
                Integer scale,
                SelectionFields.Source selection,
                String targetObjectId,
                Boolean detachRelation,
                Boolean targetRequired,
                Boolean targetUnique,
                int pageNo,
                int pageSize,
                String targetMinimum,
                String targetMaximum,
                String targetPattern,
                String targetDefaultValue) {
            this(
                    objectId,
                    detailId,
                    fieldId,
                    targetType,
                    length,
                    precision,
                    scale,
                    selection,
                    targetObjectId,
                    detachRelation,
                    targetRequired,
                    targetUnique,
                    pageNo,
                    pageSize,
                    targetMinimum,
                    targetMaximum,
                    targetPattern,
                    targetDefaultValue,
                    null);
        }
    }

    public record Result(
            String objectId,
            String detailId,
            String fieldId,
            String fieldName,
            String sourceType,
            String targetType,
            DeploymentState deploymentState,
            Long totalRows,
            Long valueRows,
            Decision decision,
            String explanation,
            List<FieldConversionDependencyInspector.Impact> impacts,
            Long deletedRows,
            Long failedRows,
            String conversionRule,
            List<Conflict> conflicts,
            Storage storage) {
        public Result(
                String objectId,
                String detailId,
                String fieldId,
                String fieldName,
                String sourceType,
                String targetType,
                DeploymentState deploymentState,
                Long totalRows,
                Long valueRows,
                Decision decision,
                String explanation,
                List<FieldConversionDependencyInspector.Impact> impacts,
                Long deletedRows,
                Long failedRows,
                String conversionRule,
                List<Conflict> conflicts) {
            this(
                    objectId,
                    detailId,
                    fieldId,
                    fieldName,
                    sourceType,
                    targetType,
                    deploymentState,
                    totalRows,
                    valueRows,
                    decision,
                    explanation,
                    impacts,
                    deletedRows,
                    failedRows,
                    conversionRule,
                    conflicts,
                    null);
        }

        public Result(
                String objectId,
                String detailId,
                String fieldId,
                String fieldName,
                String sourceType,
                String targetType,
                DeploymentState deploymentState,
                Long totalRows,
                Long valueRows,
                Decision decision,
                String explanation,
                List<FieldConversionDependencyInspector.Impact> impacts,
                Long deletedRows,
                Long failedRows,
                String conversionRule) {
            this(
                    objectId,
                    detailId,
                    fieldId,
                    fieldName,
                    sourceType,
                    targetType,
                    deploymentState,
                    totalRows,
                    valueRows,
                    decision,
                    explanation,
                    impacts,
                    deletedRows,
                    failedRows,
                    conversionRule,
                    List.of());
        }

        public Result(
                String objectId,
                String detailId,
                String fieldId,
                String fieldName,
                String sourceType,
                String targetType,
                DeploymentState deploymentState,
                Long totalRows,
                Long valueRows,
                Decision decision,
                String explanation,
                List<FieldConversionDependencyInspector.Impact> impacts,
                Long deletedRows) {
            this(
                    objectId,
                    detailId,
                    fieldId,
                    fieldName,
                    sourceType,
                    targetType,
                    deploymentState,
                    totalRows,
                    valueRows,
                    decision,
                    explanation,
                    impacts,
                    deletedRows,
                    null,
                    null);
        }

        public Result(
                String objectId,
                String detailId,
                String fieldId,
                String fieldName,
                String sourceType,
                String targetType,
                DeploymentState deploymentState,
                Long totalRows,
                Long valueRows,
                Decision decision,
                String explanation,
                List<FieldConversionDependencyInspector.Impact> impacts) {
            this(
                    objectId,
                    detailId,
                    fieldId,
                    fieldName,
                    sourceType,
                    targetType,
                    deploymentState,
                    totalRows,
                    valueRows,
                    decision,
                    explanation,
                    impacts,
                    null);
        }
    }
}
