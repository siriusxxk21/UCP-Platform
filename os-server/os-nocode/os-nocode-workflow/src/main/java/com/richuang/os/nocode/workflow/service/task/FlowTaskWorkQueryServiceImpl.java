package com.richuang.os.nocode.workflow.service.task;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.module.bpm.api.task.BpmProcessTaskApi;
import com.richuang.os.module.bpm.api.task.dto.BpmBusinessTaskDTO;
import com.richuang.os.nocode.api.work.*;
import com.richuang.os.nocode.api.workflow.FlowTaskWorkViews;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.runtime.service.work.WorkDraftQueryService;
import com.richuang.os.nocode.work.service.draft.WorkDraftService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Objects;

/** 候选只提供扫描位置；查询不调用 open/context，避免创建草稿或写入来源绑定。 */
@Service
public class FlowTaskWorkQueryServiceImpl implements FlowTaskWorkQueryService {
    @Resource private WorkDraftService drafts;
    @Resource private BpmProcessTaskApi tasks;
    @Resource private FlowTaskService flows;
    @Resource private FlowTaskBindingService bindings;
    @Resource private WorkDraftQueryService queries;
    @Resource private PlatformTransactionManager transactionManager;
    private TransactionTemplate transaction;

    @PostConstruct
    void initialize() {
        transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public FlowTaskWorkViews.Page page(FlowTaskWorkViews.Query query, long actor) {
        var candidates = drafts.flowTaskCandidates(query, actor);
        var items = new ArrayList<FlowTaskWorkViews.Item>();
        int count = Math.min(query.limit(), candidates.size());
        for (int i = 0; i < count; i++) {
            try {
                items.add(item(candidates.get(i), query.state(), actor));
            } catch (ServiceException | AccessDeniedException unavailable) {
                // 转办、取消、挂起、资源失效或撤权时不输出名称／值；基础设施错误继续上抛。
            }
        }
        var last = count == 0 ? null : candidates.get(count - 1);
        var next =
                candidates.size() > count && last != null
                        ? new WorkDraftViews.Cursor(last.createdAt().toString(), last.id())
                        : null;
        return new FlowTaskWorkViews.Page(items, next);
    }

    private FlowTaskWorkViews.Item item(
            WorkDraftViews.SourceCandidate candidate, String state, long actor) {
        var source = new WorkSourceRef(WorkSourceEnum.FLOW_TASK, candidate.sourceId());
        BpmBusinessTaskDTO task;
        String submissionId = null;
        if (WorkDraftStateEnum.SUBMITTED.matches(state)) {
            var material = flows.getSubmission(candidate.sourceId(), actor);
            if (!candidate.id().equals(material.draftId())) throw invalid("流程材料与草稿不一致");
            submissionId = material.id();
            task =
                    transaction.execute(
                            status -> {
                                var binding = bindings.lock(candidate.sourceId());
                                if (binding == null
                                        || !Long.toString(actor).equals(binding.getSubmitter())
                                        || !material.id().equals(binding.getSubmissionId()))
                                    throw invalid("流程提交材料不存在或无权访问");
                                return bindings.snapshot(binding);
                            });
        } else {
            task = tasks.getAssignedTask(actor, candidate.sourceId());
        }
        var configuration = bindings.configuration(task);
        // 上下文使用草稿保存的固定版本，并重新检查应用、对象、记录及字段权限。
        // 读取与可写性检查不放进同一外层事务，使撤销写权限能正确降级为只读。
        var context = queries.context(candidate.id(), actor, source);
        var draft = context.draft();
        if (!state.equals(draft.state())
                || !Objects.equals(draft.resource(), configuration.resource())
                || !Objects.equals(draft.objectId(), configuration.objectId())
                || draft.recordId() != null
                || !Objects.equals(
                        submissionId,
                        context.submission() == null ? null : context.submission().id()))
            throw invalid("流程草稿与当前来源或固定资源不一致");
        return new FlowTaskWorkViews.Item(
                draft.id(),
                task.taskId(),
                task.processInstanceId(),
                task.taskDefinitionKey(),
                draft.state(),
                context.formName(),
                context.model().object().objectName(),
                draft.resource().applicationId(),
                draft.resource().applicationVersion(),
                draft.updatedAt(),
                submissionId,
                context.writable(),
                context.blockedReason());
    }
}
