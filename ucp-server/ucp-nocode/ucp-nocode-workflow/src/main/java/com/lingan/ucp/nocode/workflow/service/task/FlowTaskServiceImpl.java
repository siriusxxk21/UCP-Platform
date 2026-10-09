package com.lingan.ucp.nocode.workflow.service.task;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.module.bpm.api.task.BpmProcessTaskApi;
import com.lingan.ucp.module.bpm.api.task.dto.*;
import com.lingan.ucp.nocode.api.SelectionFields;
import com.lingan.ucp.nocode.api.work.*;
import com.lingan.ucp.nocode.api.workflow.FlowTasks;
import com.lingan.ucp.nocode.enums.WorkSourceEnum;
import com.lingan.ucp.nocode.runtime.service.work.WorkDraftQueryService;
import com.lingan.ucp.nocode.runtime.service.work.WorkFormService;
import com.lingan.ucp.nocode.work.service.event.WorkEventService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

/** 业务记录、材料、来源证据、引擎推进及事件共用事务；失败不留下局部成果。 */
@Service
@Transactional(rollbackFor = Exception.class)
public class FlowTaskServiceImpl implements FlowTaskService {
    @Resource private BpmProcessTaskApi tasks;
    @Resource private FlowTaskBindingService bindings;
    @Resource private WorkFormService forms;
    @Resource private WorkDraftQueryService queries;
    @Resource private WorkEventService events;
    @Resource private com.lingan.ucp.nocode.work.service.draft.WorkDraftService drafts;

    @Override
    public FlowTasks.Workspace open(String taskId, long actor) {
        var binding = bindings.lock(taskId);
        if (binding != null && binding.getSubmissionId() != null) {
            var material = getSubmission(taskId, actor);
            var work = queries.context(material.draftId(), actor, scope(taskId));
            var snapshot = bindings.snapshot(binding);
            return new FlowTasks.Workspace(
                    snapshot.processInstanceId(), snapshot.taskDefinitionKey(), work);
        }
        var current = context(taskId, actor);
        String draftId = current.draftId();
        if (draftId == null)
            draftId = saveDraft(new FlowTasks.Save(taskId, null, null, Map.of()), actor).id();
        return new FlowTasks.Workspace(
                current.processInstanceId(),
                current.nodeId(),
                queries.context(draftId, actor, scope(taskId)));
    }

    @Override
    public SelectionFields.Result selection(FlowTasks.Selection request, long actor) {
        if (request == null || request.query() == null) throw invalid("缺少流程任务候选查询");
        var workspace = open(request.taskId(), actor);
        return queries.selection(
                new WorkDraftViews.Selection(workspace.work().draft().id(), request.query()),
                actor,
                scope(request.taskId()));
    }

    @Override
    public FlowTasks.Context context(String taskId, long actor) {
        bindings.lock(taskId);
        var task = tasks.getAssignedTask(actor, taskId);
        bindings.bind(task, actor);
        var configuration = bindings.configuration(task);
        forms.validateCreateResource(configuration.resource(), configuration.objectId(), actor);
        return new FlowTasks.Context(
                taskId,
                task.processInstanceId(),
                task.taskDefinitionKey(),
                configuration,
                drafts.currentSourceDraft(scope(taskId), actor));
    }

    @Override
    public WorkDrafts.Draft saveDraft(FlowTasks.Save command, long actor) {
        if (command == null) throw invalid("缺少流程草稿");
        var context = context(command.taskId(), actor);
        if (command.draftId() == null && context.draftId() != null) throw invalid("任务已有草稿，请先恢复后保存");
        var config = context.configuration();
        return forms.saveDraft(
                new WorkDrafts.Save(
                        command.draftId(),
                        command.expectedRevision(),
                        config.resource(),
                        config.objectId(),
                        null,
                        null,
                        command.values(),
                        command.details()),
                actor,
                scope(command.taskId()));
    }

    @Override
    public WorkDrafts.Draft getDraft(String taskId, String draftId, long actor) {
        var config = context(taskId, actor).configuration();
        var draft = forms.getDraft(draftId, actor, scope(taskId));
        checkDraft(draft, config);
        return draft;
    }

    @Override
    public WorkDrafts.Submission submit(FlowTasks.Submit command, long actor) {
        if (command == null || actor <= 0) throw invalid("流程提交参数无效");
        var digest = bindings.digest(command);
        var prior = bindings.lock(command.taskId());
        // 完成后的同一命令可以重放结果，但必须重新验证材料归属及当前业务读取权限。
        if (prior != null && prior.getSubmissionId() != null) {
            if (!Long.toString(actor).equals(prior.getSubmitter())
                    || !digest.equals(prior.getRequestDigest())) throw invalid("流程任务已提交，不能替换提交内容");
            return forms.getSubmission(prior.getSubmissionId(), actor, scope(command.taskId()));
        }
        var task = tasks.getAssignedTask(actor, command.taskId());
        bindings.bind(task, actor);
        var config = bindings.configuration(task);
        var draft = forms.getDraft(command.draftId(), actor, scope(command.taskId()));
        checkDraft(draft, config);
        var material =
                forms.submit(
                        new WorkDrafts.Submit(
                                command.draftId(),
                                command.expectedRevision(),
                                command.idempotencyKey(),
                                command.actionCode()),
                        actor,
                        scope(command.taskId()));
        bindings.submitted(command.taskId(), material.id(), digest, actor);
        // 事件先登记，后续引擎异常时与业务一起回滚；外部投递不发生于此。
        events.submitted(scope(command.taskId()), material.id(), actor);
        tasks.completeBusinessTask(
                actor,
                new BpmBusinessTaskCompleteReqDTO(
                        command.taskId(), material.id(), command.reason(), Map.of()));
        return material;
    }

    private WorkSourceRef scope(String taskId) {
        return new WorkSourceRef(WorkSourceEnum.FLOW_TASK, taskId);
    }

    @Override
    public WorkDrafts.Submission getSubmission(String taskId, long actor) {
        var binding = bindings.lock(taskId);
        if (actor <= 0
                || binding == null
                || binding.getSubmissionId() == null
                || !Long.toString(actor).equals(binding.getSubmitter()))
            throw invalid("流程提交材料不存在或无权访问");
        return forms.getSubmission(binding.getSubmissionId(), actor, scope(taskId));
    }

    private void checkDraft(WorkDrafts.Draft draft, FlowTasks.Configuration config) {
        if (!Objects.equals(draft.resource(), config.resource())
                || !Objects.equals(draft.objectId(), config.objectId())
                || draft.recordId() != null) throw invalid("草稿与当前流程节点的固定资源不一致");
    }
}
