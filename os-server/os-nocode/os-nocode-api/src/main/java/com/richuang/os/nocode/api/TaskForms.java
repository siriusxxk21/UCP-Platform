package com.richuang.os.nocode.api;

/** 表单辅助查询由任务身份确定可信版本；客户端不传入应用版本。 */
public final class TaskForms {
    private TaskForms() {}

    /** 回执身份只使用任务和业务命令键，不接受客户端应用或对象身份。 */
    public record Receipt(String taskId, String requestKey) {}

    public record CreateReceipt(String requestKey) {}

    public record CreatedReceipt(String taskId, BusinessHandling.Result handling) {}

    public record Selection(String taskId, SelectionFields.Query query) {}

    public record Fill(String taskId, FormFills.Query query) {}

    public record Related(String taskId, RelatedForms.Query query) {}

    public record RelatedSelection(String taskId, RelatedForms.Selection query) {}

    public record RelatedFill(String taskId, RelatedForms.Fill query) {}

    public record FieldRules(
            String taskId, com.richuang.os.nocode.api.FieldRules.EvaluateQuery query) {}

    public record RelatedFieldRules(String taskId, RelatedForms.FieldRules query) {}
}
