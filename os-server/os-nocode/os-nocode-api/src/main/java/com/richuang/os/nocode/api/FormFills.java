package com.richuang.os.nocode.api;

import java.util.List;

/** 来源和目标字段均来自发布表单，客户端仅提交当前选中的关系记录。 */
public final class FormFills {
    private FormFills() {}

    /** 缺省保留原有快照；仅显式开启时，清空来源才清空可写目标。 */
    public record Binding(
            String sourceFieldId, String valueFieldId, String mode, Boolean clearOnSourceEmpty) {
        public Binding(String sourceFieldId, String valueFieldId, String mode) {
            this(sourceFieldId, valueFieldId, mode, null);
        }
    }

    public record Query(
            String applicationId,
            String objectId,
            String formId,
            String sourceFieldId,
            String selectedId,
            String detailId,
            String recordId) {
        public Query(
                String applicationId,
                String objectId,
                String formId,
                String sourceFieldId,
                String selectedId) {
            this(applicationId, objectId, formId, sourceFieldId, selectedId, null, null);
        }
    }

    /** 设计预览携带当前对象引用和未发布表单；query.formId 不参与解析。 */
    public record PreviewQuery(
            Query query,
            List<ApplicationCenter.ObjectReference> objects,
            ApplicationUi.Form form) {}
}
