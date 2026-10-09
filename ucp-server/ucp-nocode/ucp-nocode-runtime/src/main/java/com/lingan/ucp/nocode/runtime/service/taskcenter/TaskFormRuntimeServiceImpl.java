package com.lingan.ucp.nocode.runtime.service.taskcenter;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.published.ApplicationPublishedService;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.record.RelatedFormService;
import com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** 任务资格与实时表单权限校验完成后才固定版本；辅助接口不授予任意应用访问权。 */
@Service
public class TaskFormRuntimeServiceImpl implements TaskFormRuntimeService {
    @Resource private TaskCenterService tasks;
    @Resource private ApplicationPublishedService published;
    @Resource private TaskEntryRuntimeService entries;
    @Resource private RelatedFormService relatedForms;
    @Resource private com.lingan.ucp.nocode.runtime.dal.mapper.TaskCenterMapper taskBindings;

    @Resource(name = "nocodeRecordService")
    private RecordService records;

    private record Context(TaskCenter.BusinessRef ref, TaskEntries.Locator entry) {}

    @Override
    public BusinessHandling.Result receipt(TaskForms.Receipt query, long actor) {
        return tasks.businessReceipt(query, actor);
    }

    @Override
    public TaskForms.CreatedReceipt createReceipt(TaskForms.CreateReceipt query, long actor) {
        return tasks.createReceipt(query, actor);
    }

    @Override
    public String handlingTask(String requestId, long actor) {
        if (requestId == null || requestId.isBlank()) throw invalid("缺少申请身份");
        String task = taskBindings.handlingTaskId(requestId, actor);
        if (task != null) tasks.detail(task, actor);
        return task;
    }

    private <T> T execute(String id, long actor, Function<Context, T> action) {
        TaskCenter.FormContext form = tasks.form(id, actor);
        TaskCenter.Binding binding = tasks.detail(id, actor).task().binding();
        TaskCenter.BusinessRef ref = form.binding();
        TaskEntries.Locator entry =
                binding == null || binding.entryId() == null
                        ? null
                        : new TaskEntries.Locator(
                                ref.resource().applicationId(),
                                binding.entryId(),
                                ref.resource().applicationVersion());
        return published.withVersion(ref.resource(), () -> action.apply(new Context(ref, entry)));
    }

    @Override
    public FieldRules.Evaluation fieldRules(TaskForms.FieldRules request, long actor) {
        if (request == null || request.query() == null) throw invalid("缺少表单求值条件");
        return execute(
                request.taskId(),
                actor,
                c -> {
                    FieldRules.EvaluateQuery query = request.query();
                    target(
                            c,
                            query.applicationId(),
                            query.objectId(),
                            query.formId(),
                            query.recordId());
                    return c.entry() == null
                            ? records.evaluateRules(query, actor)
                            : entries.fieldRules(
                                    new TaskEntries.FieldRules(c.entry(), query), actor);
                });
    }

    @Override
    public FieldRules.Evaluation relatedFieldRules(
            TaskForms.RelatedFieldRules request, long actor) {
        if (request == null || request.query() == null || request.query().query() == null)
            throw invalid("缺少关联表单求值条件");
        return execute(
                request.taskId(),
                actor,
                c -> {
                    context(c, request.query().context());
                    return c.entry() == null
                            ? relatedForms.fieldRules(request.query(), actor)
                            : entries.relatedFieldRules(
                                    new RelatedForms.TaskFieldRules(c.entry(), request.query()),
                                    actor);
                });
    }

    private void target(Context c, String app, String object, String form, String record) {
        if (!Objects.equals(c.ref().resource().applicationId(), app)
                || !Objects.equals(c.ref().object().objectId(), object)
                || !Objects.equals(c.ref().resource().resourceId(), form)
                || !Objects.equals(c.ref().recordId(), record))
            throw invalid("辅助查询必须属于当前任务固定表单和业务记录");
    }

    private void context(Context c, RelatedForms.Query query) {
        if (query == null) throw invalid("缺少关联表单上下文");
        target(c, query.applicationId(), query.objectId(), query.formId(), query.recordId());
    }

    @Override
    public SelectionFields.Result selection(TaskForms.Selection request, long actor) {
        if (request == null || request.query() == null) throw invalid("缺少字段候选条件");
        return execute(
                request.taskId(),
                actor,
                c -> {
                    SelectionFields.Query q = request.query();
                    target(c, q.applicationId(), q.objectId(), q.formId(), q.recordId());
                    return c.entry() == null
                            ? records.selection(q, actor)
                            : entries.selection(new TaskEntries.Selection(c.entry(), q), actor);
                });
    }

    @Override
    public Map<String, Object> fill(TaskForms.Fill request, long actor) {
        if (request == null || request.query() == null) throw invalid("缺少表单带入条件");
        return execute(
                request.taskId(),
                actor,
                c -> {
                    FormFills.Query q = request.query();
                    target(c, q.applicationId(), q.objectId(), q.formId(), q.recordId());
                    return c.entry() == null
                            ? records.formFill(q, actor)
                            : entries.formFill(new TaskEntries.FormFill(c.entry(), q), actor);
                });
    }

    @Override
    public RelatedForms.Result related(TaskForms.Related request, long actor) {
        if (request == null || request.query() == null) throw invalid("缺少关联表单条件");
        return execute(
                request.taskId(),
                actor,
                c -> {
                    context(c, request.query());
                    return c.entry() == null
                            ? relatedForms.query(request.query(), actor)
                            : entries.relatedForm(
                                    new RelatedForms.TaskQuery(c.entry(), request.query()), actor);
                });
    }

    @Override
    public SelectionFields.Result relatedSelection(TaskForms.RelatedSelection request, long actor) {
        if (request == null || request.query() == null) throw invalid("缺少关联候选条件");
        return execute(
                request.taskId(),
                actor,
                c -> {
                    context(c, request.query().context());
                    return c.entry() == null
                            ? relatedForms.selection(request.query(), actor)
                            : entries.relatedSelection(
                                    new RelatedForms.TaskSelection(c.entry(), request.query()),
                                    actor);
                });
    }

    @Override
    public Map<String, Object> relatedFill(TaskForms.RelatedFill request, long actor) {
        if (request == null || request.query() == null) throw invalid("缺少关联带入条件");
        return execute(
                request.taskId(),
                actor,
                c -> {
                    context(c, request.query().context());
                    return c.entry() == null
                            ? relatedForms.fill(request.query(), actor)
                            : entries.relatedFill(
                                    new RelatedForms.TaskFill(c.entry(), request.query()), actor);
                });
    }
}
