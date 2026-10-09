package com.lingan.ucp.nocode.runtime.service.task;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.work.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.work.WorkFormService;
import com.lingan.ucp.nocode.work.service.draft.WorkDraftService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.*;

/** 入口授权只在受控同步调用中生效；业务保存仍交给公共 RecordService，不提供通用转发代理。 */
@Service
public class TaskEntryRuntimeServiceImpl implements TaskEntryRuntimeService {
    @Resource private TaskEntryDraftAccess draftAccess;
    @Resource private TaskEntryRuntimeInvocation invocations;
    @Resource private TaskEntryRuntimeQueries queries;

    @Resource(name = "nocodeRecordService")
    private RecordService records;

    @Resource private WorkFormService workForms;
    @Resource private WorkDraftService drafts;
    @Resource private com.lingan.ucp.nocode.runtime.dal.mapper.RecordHistoryMapper recordHistory;

    @Override
    public <T> T readHistoryScope(
            TaskEntries.Locator entry,
            String formId,
            String recordId,
            long actor,
            java.util.function.Supplier<T> action) {
        return invocations.execute(
                entry,
                actor,
                true,
                resolved -> {
                    if (!Objects.equals(formId, resolved.config().formId()))
                        throw invalid("任务历史引用的表单已变化");
                    if (TaskEntryModeEnum.FORM.matches(resolved.config().mode())
                            && !recordHistory.createdFromTask(
                                    entry.applicationId(),
                                    resolved.config().objectId(),
                                    entry.entryId(),
                                    recordId,
                                    Long.toString(actor))) throw invalid("只能查看本人通过此入口提交的业务记录");
                    return action.get();
                });
    }

    @Resource
    private org.springframework.beans.factory.ObjectProvider<
                    com.lingan.ucp.nocode.runtime.service.handling.BusinessHandlingService>
            handling;

    @Override
    public ApplicationRecords.Aggregate applyHandling(
            TaskEntries.Locator entry,
            ApplicationRecords.Save command,
            WorkDrafts.Submission material,
            long actor) {
        if (!com.lingan.ucp.nocode.runtime.service.handling.HandlingWriteScope.permits(
                command, actor)) throw invalid("不允许直接调用审批生效");
        return invocations.execute(
                entry,
                actor,
                true,
                r -> {
                    invocations.requireTarget(r, command.applicationId(), command.objectId());
                    if (!Objects.equals(command.formId(), r.config().formId()))
                        throw invalid("任务表单已变化");
                    if (command.id() != null) invocations.requireList(r);
                    com.lingan.ucp.nocode.runtime.service.handling.BusinessHandlingServiceImpl
                            .assertUnchanged(records.prepareHandling(command, actor), material);
                    return records.save(command, actor);
                });
    }

    @Override
    public BusinessHandling.Result submit(TaskEntries.Save command, long actor) {
        return invocations.execute(
                command.entry(),
                actor,
                true,
                r -> {
                    ApplicationRecords.Save input = command.record();
                    if (input == null || input.context() != null || input.actionCode() != null)
                        throw invalid("不支持的入口提交动作");
                    invocations.requireTarget(r, input.applicationId(), input.objectId());
                    if (input.id() != null) invocations.requireList(r);
                    if (r.config().formId() == null) throw invalid("当前入口没有可填写表单");
                    String key = draftAccess.requestKey(r.resource().id(), input.requestKey());
                    BusinessHandling.Result successful =
                            handling.getObject()
                                    .receipt(
                                            new BusinessHandling.Receipt(
                                                    input.applicationId(), input.objectId(), key),
                                            actor);
                    if (successful != null) {
                        // 仍交给公共提交核对请求摘要，不能把另一份输入当成原申请返回。
                        return handling.getObject()
                                .submit(
                                        new ApplicationRecords.Save(
                                                input.applicationId(),
                                                input.objectId(),
                                                input.id(),
                                                input.expectedRevision(),
                                                input.values(),
                                                input.details(),
                                                input.relations(),
                                                null,
                                                r.config().formId(),
                                                key,
                                                null,
                                                input.relatedRecords()),
                                        actor);
                    }
                    if (command.draft() != null) {
                        WorkDrafts.Draft draft =
                                workForms.getDraft(
                                        command.draft().id(), actor, draftAccess.source(r));
                        draftAccess.requireRelatedDraft(input, draft);
                        if (input.id() != null
                                || draft.recordId() != null
                                || !draft.resource().equals(draftAccess.formRef(r))
                                || draft.revision() != command.draft().revision()
                                || !WorkDraftStateEnum.DRAFT.matches(draft.state()))
                            throw invalid("暂存草稿已变化，请重新恢复");
                    }
                    BusinessHandling.Result result =
                            handling.getObject()
                                    .submit(
                                            new ApplicationRecords.Save(
                                                    input.applicationId(),
                                                    input.objectId(),
                                                    input.id(),
                                                    input.expectedRevision(),
                                                    input.values(),
                                                    input.details(),
                                                    input.relations(),
                                                    null,
                                                    r.config().formId(),
                                                    key,
                                                    null,
                                                    input.relatedRecords()),
                                            actor);
                    if (command.draft() != null)
                        drafts.markSubmitted(
                                command.draft().id(),
                                command.draft().revision(),
                                actor,
                                draftAccess.source(r));
                    return result;
                });
    }

    @Override
    public ApplicationRecords.Aggregate save(TaskEntries.Save command, long actor) {
        return invocations.execute(
                command.entry(),
                actor,
                true,
                r -> {
                    ApplicationRecords.Save input = command.record();
                    if (input == null || input.context() != null || input.actionCode() != null)
                        throw invalid("不支持的入口保存动作");
                    invocations.requireTarget(r, input.applicationId(), input.objectId());
                    if (input.id() != null) invocations.requireList(r);
                    if (r.config().formId() == null) throw invalid("当前入口没有可填写表单");
                    String key = draftAccess.requestKey(r.resource().id(), input.requestKey());
                    if (command.draft() != null) {
                        ApplicationRecords.SaveReceipt receipt =
                                records.receipt(
                                        input.applicationId(), input.objectId(), key, actor);
                        if (DocumentReceiptStatusEnum.SUCCEEDED.matches(receipt.status())
                                && receipt.result() != null)
                            return records.save(
                                    new ApplicationRecords.Save(
                                            input.applicationId(),
                                            input.objectId(),
                                            input.id(),
                                            input.expectedRevision(),
                                            input.values(),
                                            input.details(),
                                            input.relations(),
                                            null,
                                            r.config().formId(),
                                            key,
                                            null,
                                            input.relatedRecords()),
                                    actor);
                        WorkDrafts.Draft draft =
                                workForms.getDraft(
                                        command.draft().id(), actor, draftAccess.source(r));
                        draftAccess.requireRelatedDraft(input, draft);
                        if (input.id() != null
                                || draft.recordId() != null
                                || !draft.resource().equals(draftAccess.formRef(r))
                                || draft.revision() != command.draft().revision()
                                || !WorkDraftStateEnum.DRAFT.matches(draft.state()))
                            throw invalid("暂存草稿已变化，请保留输入并重新恢复");
                    }
                    ApplicationRecords.Aggregate saved =
                            records.save(
                                    new ApplicationRecords.Save(
                                            input.applicationId(),
                                            input.objectId(),
                                            input.id(),
                                            input.expectedRevision(),
                                            input.values(),
                                            input.details(),
                                            input.relations(),
                                            null,
                                            r.config().formId(),
                                            key,
                                            null,
                                            input.relatedRecords()),
                                    actor);
                    if (command.draft() != null)
                        drafts.markSubmitted(
                                command.draft().id(),
                                command.draft().revision(),
                                actor,
                                draftAccess.source(r));
                    return saved;
                });
    }

    @Override
    public void delete(TaskEntries.Delete command, long actor) {
        invocations.execute(
                command.entry(),
                actor,
                true,
                r -> {
                    invocations.requireList(r);
                    records.delete(
                            new ApplicationRecords.Delete(
                                    command.entry().applicationId(),
                                    r.config().objectId(),
                                    command.recordId(),
                                    command.expectedRevision()),
                            actor);
                    return null;
                });
    }

    @Override
    public RelatedForms.Result relatedForm(RelatedForms.TaskQuery query, long actor) {
        return queries.relatedForm(query, actor);
    }

    @Override
    public SelectionFields.Result relatedSelection(RelatedForms.TaskSelection query, long actor) {
        return queries.relatedSelection(query, actor);
    }

    @Override
    public Map<String, Object> relatedFill(RelatedForms.TaskFill query, long actor) {
        return queries.relatedFill(query, actor);
    }

    @Override
    public BusinessHandling.Result handlingReceipt(TaskEntries.Receipt query, long actor) {
        return queries.handlingReceipt(query, actor);
    }

    @Override
    public List<TaskEntries.Card> mine(long actor) {
        return queries.mine(actor);
    }

    @Override
    public TaskEntries.Context context(TaskEntries.Locator entry, long actor) {
        return queries.context(entry, actor);
    }

    @Override
    public PageResult<ApplicationRecords.Row> page(TaskEntries.Query command, long actor) {
        return queries.page(command, actor);
    }

    @Override
    public DataViews.Model viewModel(TaskEntries.ViewModel command, long actor) {
        return queries.viewModel(command, actor);
    }

    @Override
    public PageResult<ApplicationRecords.Row> viewChildren(
            TaskEntries.ViewChildren command, long actor) {
        return queries.viewChildren(command, actor);
    }

    @Override
    public ApplicationRecords.Aggregate get(TaskEntries.Get command, long actor) {
        return queries.get(command, actor);
    }

    @Override
    public SelectionFields.Result selection(TaskEntries.Selection command, long actor) {
        return queries.selection(command, actor);
    }

    @Override
    public Map<String, Object> formFill(TaskEntries.FormFill command, long actor) {
        return queries.formFill(command, actor);
    }

    @Override
    public FieldRules.Evaluation fieldRules(TaskEntries.FieldRules command, long actor) {
        return queries.fieldRules(command, actor);
    }

    @Override
    public FieldRules.Evaluation relatedFieldRules(
            RelatedForms.TaskFieldRules command, long actor) {
        return queries.relatedFieldRules(command, actor);
    }

    @Override
    public ApplicationRecords.SaveReceipt receipt(TaskEntries.Receipt command, long actor) {
        return queries.receipt(command, actor);
    }

    @Override
    public TaskEntries.DraftPage drafts(TaskEntries.DraftQuery query, long actor) {
        return draftAccess.drafts(query, actor);
    }

    @Override
    public WorkDrafts.Draft draft(TaskEntries.Locator entry, long actor) {
        return draftAccess.draft(entry, actor);
    }

    @Override
    public WorkDrafts.Draft saveDraft(TaskEntries.Save command, long actor) {
        return draftAccess.saveDraft(command, actor);
    }

    @Override
    public TaskEntries.Activities activity(TaskEntries.ActivityQuery command, long actor) {
        return queries.activity(command, actor);
    }
}
