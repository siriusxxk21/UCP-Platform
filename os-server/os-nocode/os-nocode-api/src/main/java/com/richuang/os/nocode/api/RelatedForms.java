package com.richuang.os.nocode.api;

import java.util.List;
import java.util.Map;

/** 独立关联数据的同表单编辑；关系及目标表单只从发布配置解析。 */
public final class RelatedForms {
    private RelatedForms() {}

    public record Binding(
            String id,
            String sourceObjectId,
            String relationId,
            String direction,
            String formId,
            String title) {}

    /** 解除关系不会删除目标记录。未提交的记录保持原样，不按当前可见列表整组替换。 */
    public record Row(
            String id,
            String expectedRevision,
            Map<String, Object> values,
            Map<String, List<ApplicationRecords.Row>> details,
            boolean unlink) {}

    public record Query(
            String applicationId,
            String objectId,
            String formId,
            String bindingId,
            String recordId,
            String selectedId,
            String search) {}

    public record Result(
            ApplicationRecords.Model model,
            ApplicationUi.Form form,
            List<ApplicationRecords.Aggregate> records,
            boolean multiple,
            String linkFieldId,
            boolean required,
            boolean truncated) {}

    public record TaskQuery(TaskEntries.Locator entry, Query query) {}

    public record Selection(Query context, SelectionFields.Query query) {}

    public record Fill(Query context, FormFills.Query query) {}

    public record TaskSelection(TaskEntries.Locator entry, Selection request) {}

    public record TaskFill(TaskEntries.Locator entry, Fill request) {}

    /** 父表单绑定身份与子表单求值身份同时核验，不接受任意目标对象。 */
    public record FieldRules(
            Query context, com.richuang.os.nocode.api.FieldRules.EvaluateQuery query) {}

    public record TaskFieldRules(TaskEntries.Locator entry, FieldRules request) {}
}
