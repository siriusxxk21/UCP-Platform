package com.lingan.ucp.nocode.api;

import com.lingan.ucp.nocode.enums.FieldConversionActionEnum;

import java.util.List;

/** 字段变更的影响预览与按列清空确认；不接收客户端物理表名或 SQL。 */
public final class FieldConversions {
    private FieldConversions() {}

    /** 指纹绑定全量原值及记录身份，分页只用于展示，不改变清空范围。 */
    public record Change(
            String fieldId,
            String detailId,
            String fieldName,
            String sourceName,
            String fromType,
            String toType,
            String fromFieldType,
            String toFieldType,
            long affectedRows,
            long deletedRows,
            boolean masked,
            String fingerprint,
            boolean clearAllowed,
            List<FieldConversionDependencyInspector.Impact> impacts,
            String action,
            String conversionRule,
            long failedRows,
            List<FieldSwitchPreview.Conflict> conflicts) {
        public Change {
            if (action == null) action = FieldConversionActionEnum.CLEAR_COLUMN.getCode();
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }

        public Change(
                String fieldId,
                String detailId,
                String fieldName,
                String sourceName,
                String fromType,
                String toType,
                String fromFieldType,
                String toFieldType,
                long affectedRows,
                long deletedRows,
                boolean masked,
                String fingerprint,
                boolean clearAllowed,
                List<FieldConversionDependencyInspector.Impact> impacts,
                String action,
                String conversionRule,
                long failedRows) {
            this(
                    fieldId,
                    detailId,
                    fieldName,
                    sourceName,
                    fromType,
                    toType,
                    fromFieldType,
                    toFieldType,
                    affectedRows,
                    deletedRows,
                    masked,
                    fingerprint,
                    clearAllowed,
                    impacts,
                    action,
                    conversionRule,
                    failedRows,
                    List.of());
        }

        public Change(
                String fieldId,
                String detailId,
                String fieldName,
                String sourceName,
                String fromType,
                String toType,
                String fromFieldType,
                String toFieldType,
                long affectedRows,
                long deletedRows,
                boolean masked,
                String fingerprint,
                boolean clearAllowed,
                List<FieldConversionDependencyInspector.Impact> impacts) {
            this(
                    fieldId,
                    detailId,
                    fieldName,
                    sourceName,
                    fromType,
                    toType,
                    fromFieldType,
                    toFieldType,
                    affectedRows,
                    deletedRows,
                    masked,
                    fingerprint,
                    clearAllowed,
                    impacts,
                    FieldConversionActionEnum.CLEAR_COLUMN.getCode(),
                    null,
                    0);
        }
    }

    /** 敏感值在服务端遮蔽；逻辑删除行单独标记，记录本身不会被删除。 */
    public record Row(
            String id,
            String title,
            String parentId,
            String oldValue,
            boolean deleted,
            String newValue,
            String failureReason,
            List<String> failureCodes) {
        public Row(
                String id,
                String title,
                String parentId,
                String oldValue,
                boolean deleted,
                String newValue,
                String failureReason) {
            this(id, title, parentId, oldValue, deleted, newValue, failureReason, List.of());
        }

        public Row(String id, String title, String parentId, String oldValue, boolean deleted) {
            this(id, title, parentId, oldValue, deleted, null, null, List.of());
        }
    }

    public record Page(List<Row> rows, long total, int pageNo, int pageSize) {}
}
