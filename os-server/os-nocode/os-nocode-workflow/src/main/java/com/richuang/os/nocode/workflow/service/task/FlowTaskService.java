package com.richuang.os.nocode.workflow.service.task;

import com.richuang.os.nocode.api.SelectionFields;
import com.richuang.os.nocode.api.work.WorkDrafts;
import com.richuang.os.nocode.api.workflow.FlowTasks;

/** 流程节点新增业务的正式来源入口，始终使用当前办理人及实时业务授权。 */
public interface FlowTaskService {
    FlowTasks.Context context(String taskId, long actor);

    FlowTasks.Workspace open(String taskId, long actor);

    SelectionFields.Result selection(FlowTasks.Selection request, long actor);

    WorkDrafts.Draft saveDraft(FlowTasks.Save command, long actor);

    WorkDrafts.Draft getDraft(String taskId, String draftId, long actor);

    WorkDrafts.Submission submit(FlowTasks.Submit command, long actor);

    WorkDrafts.Submission getSubmission(String taskId, long actor);
}
