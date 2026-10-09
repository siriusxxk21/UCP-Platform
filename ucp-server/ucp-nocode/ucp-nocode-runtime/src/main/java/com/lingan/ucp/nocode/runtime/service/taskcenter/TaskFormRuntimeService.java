package com.lingan.ucp.nocode.runtime.service.taskcenter;

import com.lingan.ucp.nocode.api.*;

import java.util.Map;

/** 存量任务表单的候选与带入查询，复用现有业务权限和表单服务。 */
public interface TaskFormRuntimeService {
    /** 旧申请页重提时回到原任务，避免新申请失去任务跟踪。 */
    String handlingTask(String requestId, long actor);

    BusinessHandling.Result receipt(TaskForms.Receipt query, long actor);

    TaskForms.CreatedReceipt createReceipt(TaskForms.CreateReceipt query, long actor);

    SelectionFields.Result selection(TaskForms.Selection request, long actor);

    Map<String, Object> fill(TaskForms.Fill request, long actor);

    RelatedForms.Result related(TaskForms.Related request, long actor);

    SelectionFields.Result relatedSelection(TaskForms.RelatedSelection request, long actor);

    Map<String, Object> relatedFill(TaskForms.RelatedFill request, long actor);

    FieldRules.Evaluation fieldRules(TaskForms.FieldRules request, long actor);

    FieldRules.Evaluation relatedFieldRules(TaskForms.RelatedFieldRules request, long actor);
}
