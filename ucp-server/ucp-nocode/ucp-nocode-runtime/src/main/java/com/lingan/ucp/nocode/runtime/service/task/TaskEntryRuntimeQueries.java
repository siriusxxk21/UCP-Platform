package com.lingan.ucp.nocode.runtime.service.task;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.resource.ApplicationResourceValidator;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.runtime.dal.mapper.RecordHistoryMapper;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.application.ApplicationRuntimeService;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.record.RelatedFormService;
import com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeInvocation.Resolved;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 任务入口的卡片、上下文、记录和活动读取；每次调用均进入可信作用域。 */
@Component
public class TaskEntryRuntimeQueries {
    @Resource private TaskEntryDraftAccess draftAccess;
    @Resource private TaskEntryRuntimeInvocation invocations;
    @Resource private com.lingan.ucp.nocode.runtime.service.view.DataViewService dataViews;
    @Resource private ApplicationService applications;
    @Resource private ApplicationResourceValidator resources;
    @Resource private ApplicationRuntimeService runtime;
    @Resource private RelatedFormService relatedForms;

    @Resource(name = "nocodeRecordService")
    private RecordService records;

    @Resource private ObjectMapper json;
    @Resource private RecordHistoryMapper history;
    @Resource private ApplicationRuntimePolicy runtimePolicy;
    @Resource private DataObjectApi objects;

    @Resource
    private org.springframework.beans.factory.ObjectProvider<
                    com.lingan.ucp.nocode.runtime.service.handling.BusinessHandlingService>
            handling;

    public RelatedForms.Result relatedForm(RelatedForms.TaskQuery query, long actor) {
        return invocations.execute(
                query.entry(),
                actor,
                true,
                r -> {
                    invocations.requireRelatedContext(r, query.query(), actor);
                    return relatedForms.query(query.query(), actor);
                });
    }

    public SelectionFields.Result relatedSelection(RelatedForms.TaskSelection query, long actor) {
        return invocations.execute(
                query.entry(),
                actor,
                true,
                r -> {
                    RelatedForms.Query context = query.request().context();
                    invocations.requireRelatedContext(r, context, actor);
                    return relatedForms.selection(query.request(), actor);
                });
    }

    public Map<String, Object> relatedFill(RelatedForms.TaskFill query, long actor) {
        return invocations.execute(
                query.entry(),
                actor,
                true,
                r -> {
                    RelatedForms.Query context = query.request().context();
                    invocations.requireRelatedContext(r, context, actor);
                    return relatedForms.fill(query.request(), actor);
                });
    }

    public FieldRules.Evaluation relatedFieldRules(RelatedForms.TaskFieldRules query, long actor) {
        return invocations.execute(
                query.entry(),
                actor,
                true,
                r -> {
                    invocations.requireRelatedContext(r, query.request().context(), actor);
                    return relatedForms.fieldRules(query.request(), actor);
                });
    }

    public BusinessHandling.Result handlingReceipt(TaskEntries.Receipt query, long actor) {
        return invocations.execute(
                query.entry(),
                actor,
                true,
                r ->
                        handling.getObject()
                                .receipt(
                                        new BusinessHandling.Receipt(
                                                query.entry().applicationId(),
                                                r.config().objectId(),
                                                draftAccess.requestKey(
                                                        r.resource().id(), query.requestKey())),
                                        actor));
    }

    TaskEntries.Card card(Resolved r) {
        TaskEntries.Config c = r.config();
        return new TaskEntries.Card(
                r.release().application().id(),
                r.release().application().name(),
                r.resource().id(),
                r.resource().name(),
                c.category(),
                c.description(),
                c.icon(),
                c.mode(),
                c.sortOrder(),
                r.release().versionNo());
    }

    public List<TaskEntries.Card> mine(long actor) {
        if (actor <= 0) throw invalid("请先登录");
        List<TaskEntries.Card> result = new ArrayList<>();
        for (String app : applications.runnableIds()) {
            ApplicationCenter.Published release;
            try {
                release = applications.published(app);
            } catch (ServiceException unavailable) {
                // 目录枚举后应用可能被停用或删除；一个失效应用不能中断其他可用办理入口。
                continue;
            }
            for (ApplicationCenter.Resource resource : release.definition().resources()) {
                if (!ApplicationResourceKindEnum.TASK_ENTRY.matches(resource.kind())) continue;
                try {
                    result.add(
                            card(
                                    invocations.resolve(
                                            new TaskEntries.Locator(app, resource.id(), null),
                                            actor,
                                            false)));
                } catch (ServiceException unavailable) {
                    /* 不向员工泄露未开放、无权限的入口名称。 */
                }
            }
        }
        return result.stream()
                .sorted(
                        Comparator.comparing(TaskEntries.Card::category)
                                .thenComparingInt(TaskEntries.Card::sortOrder)
                                .thenComparing(TaskEntries.Card::name))
                .toList();
    }

    public TaskEntries.Context context(TaskEntries.Locator entry, long actor) {
        return invocations.execute(
                entry,
                actor,
                false,
                r -> {
                    TaskEntries.Config c = r.config();
                    ApplicationCenter.Published visible =
                            runtime.application(entry.applicationId(), actor);
                    Set<String> ids = new HashSet<>();
                    if (c.formId() != null) ids.add(c.formId());
                    if (c.viewId() != null) {
                        ids.add(c.viewId());
                        ApplicationCenter.Resource viewResource =
                                visible.definition().resources().stream()
                                        .filter(v -> v.id().equals(c.viewId()))
                                        .findFirst()
                                        .orElseThrow();
                        ApplicationUi.View view =
                                resources.decode(viewResource.config(), ApplicationUi.View.class);
                        if (view.filterDictionaries() != null)
                            ids.addAll(view.filterDictionaries().values());
                    }
                    List<ApplicationCenter.Resource> selected =
                            visible.definition().resources().stream()
                                    .filter(v -> ids.contains(v.id()))
                                    .map(
                                            v -> {
                                                if (!ApplicationResourceKindEnum.VIEW.matches(
                                                        v.kind())) return v;
                                                ApplicationUi.View view =
                                                        resources.decode(
                                                                v.config(),
                                                                ApplicationUi.View.class);
                                                // 可见按钮不产生授权；首期无专用适配的动作与导入导出不出现在办理界面。
                                                List<String> crud =
                                                        List.of(
                                                                ViewButtonEnum.VIEW.getCode(),
                                                                ViewButtonEnum.CREATE.getCode(),
                                                                ViewButtonEnum.UPDATE.getCode(),
                                                                ViewButtonEnum.DELETE.getCode());
                                                List<String> buttons =
                                                        view.interaction() == null
                                                                        || view.interaction()
                                                                                        .buttons()
                                                                                == null
                                                                ? crud
                                                                : view
                                                                        .interaction()
                                                                        .buttons()
                                                                        .stream()
                                                                        .filter(crud::contains)
                                                                        .toList();
                                                ApplicationUi.ViewInteraction interaction =
                                                        new ApplicationUi.ViewInteraction(
                                                                buttons,
                                                                List.of(),
                                                                RecordOpenModeEnum.MODAL.getCode(),
                                                                RecordOpenModeEnum.MODAL.getCode());
                                                ApplicationUi.ViewList list =
                                                        view.list() == null
                                                                ? null
                                                                : new ApplicationUi.ViewList(
                                                                        view.list().queryFieldIds(),
                                                                        view.list()
                                                                                .advancedFieldIds(),
                                                                        view.list().columnWidths(),
                                                                        false,
                                                                        view.list().overflow());
                                                var safe =
                                                        new ApplicationUi.View(
                                                                view.objectId(),
                                                                view.fieldIds(),
                                                                view.equal(),
                                                                view.sortFieldId(),
                                                                view.descending(),
                                                                view.pageSize(),
                                                                c.formId(),
                                                                view.filterDictionaries(),
                                                                null,
                                                                interaction,
                                                                list,
                                                                view.query(),
                                                                view.composition());
                                                return new ApplicationCenter.Resource(
                                                        v.id(),
                                                        v.kind(),
                                                        v.code(),
                                                        v.name(),
                                                        json.convertValue(
                                                                safe,
                                                                new com.fasterxml.jackson.core.type
                                                                                .TypeReference<
                                                                        Map<String, Object>>() {}));
                                            })
                                    .toList();
                    TaskEntries.Config presentation =
                            new TaskEntries.Config(
                                    c.objectId(),
                                    c.viewId(),
                                    c.formId(),
                                    c.mode(),
                                    c.category(),
                                    c.description(),
                                    c.icon(),
                                    c.sortOrder(),
                                    List.of());
                    return new TaskEntries.Context(
                            card(r),
                            presentation,
                            records.model(entry.applicationId(), c.objectId(), actor),
                            selected);
                });
    }

    public PageResult<ApplicationRecords.Row> page(TaskEntries.Query command, long actor) {
        return invocations.execute(
                command.entry(),
                actor,
                true,
                r -> {
                    invocations.requireList(r);
                    ApplicationRecords.Query q = command.query();
                    if (q == null || q.context() != null) throw invalid("不支持的入口查询上下文");
                    invocations.requireTarget(r, q.applicationId(), q.objectId());
                    return records.page(
                            new ApplicationRecords.Query(
                                    q.applicationId(),
                                    q.objectId(),
                                    q.pageNo(),
                                    q.pageSize(),
                                    q.search(),
                                    q.equal(),
                                    q.sortFieldId(),
                                    q.descending(),
                                    r.config().viewId(),
                                    null,
                                    q.conditions(),
                                    q.childFilters()),
                            actor);
                });
    }

    public DataViews.Model viewModel(TaskEntries.ViewModel command, long actor) {
        return invocations.execute(
                command.entry(),
                actor,
                true,
                r -> {
                    invocations.requireList(r);
                    invocations.requireTarget(
                            r, command.entry().applicationId(), command.objectId());
                    if (!Objects.equals(command.viewId(), r.config().viewId()))
                        throw invalid("视图不属于任务入口");
                    return dataViews.model(
                            command.entry().applicationId(),
                            command.objectId(),
                            r.config().viewId(),
                            actor);
                });
    }

    public PageResult<ApplicationRecords.Row> viewChildren(
            TaskEntries.ViewChildren command, long actor) {
        return invocations.execute(
                command.entry(),
                actor,
                true,
                r -> {
                    invocations.requireList(r);
                    DataViews.ChildQuery query = command.query();
                    invocations.requireTarget(r, query.applicationId(), query.objectId());
                    if (!Objects.equals(query.viewId(), r.config().viewId()))
                        throw invalid("视图不属于任务入口");
                    return dataViews.children(query, actor);
                });
    }

    public ApplicationRecords.Aggregate get(TaskEntries.Get command, long actor) {
        return invocations.execute(
                command.entry(),
                actor,
                true,
                r -> {
                    boolean form = TaskEntryModeEnum.FORM.matches(r.config().mode());
                    if (form
                            && !history.createdFromTask(
                                    command.entry().applicationId(),
                                    r.config().objectId(),
                                    r.resource().id(),
                                    command.recordId(),
                                    Long.toString(actor))) throw invalid("只能查看本人通过此入口提交的业务记录");
                    ApplicationRecords.Aggregate result =
                            records.get(
                                    command.entry().applicationId(),
                                    r.config().objectId(),
                                    command.recordId(),
                                    actor);
                    if (!form) return result;
                    Map<String, List<ApplicationRecords.Row>> details = new LinkedHashMap<>();
                    result.details()
                            .forEach(
                                    (id, rows) ->
                                            details.put(
                                                    id,
                                                    rows.stream().map(this::readOnly).toList()));
                    return new ApplicationRecords.Aggregate(
                            readOnly(result.record()),
                            details,
                            result.processes(),
                            result.relations());
                });
    }

    ApplicationRecords.Row readOnly(ApplicationRecords.Row row) {
        ApplicationAuthorization.Capabilities caps = row.permissions();
        if (caps == null) return row;
        return new ApplicationRecords.Row(
                row.id(),
                row.revision(),
                row.values(),
                new ApplicationAuthorization.Capabilities(
                        Set.of(ApplicationActionEnum.READ.getCode()),
                        caps.readFields(),
                        Set.of(),
                        caps.readDetails(),
                        Set.of(),
                        caps.readRelations(),
                        Set.of()),
                row.displayValues(),
                row.clientRowKey(),
                row.parentId());
    }

    public SelectionFields.Result selection(TaskEntries.Selection command, long actor) {
        return invocations.execute(
                command.entry(),
                actor,
                true,
                r -> {
                    SelectionFields.Query q = command.query();
                    if (q == null) throw invalid("缺少选择字段");
                    invocations.requireTarget(r, q.applicationId(), q.objectId());
                    if (q.recordId() != null) invocations.requireList(r);
                    return records.selection(
                            new SelectionFields.Query(
                                    q.applicationId(),
                                    q.objectId(),
                                    q.detailId(),
                                    q.fieldId(),
                                    q.search(),
                                    q.pageNo(),
                                    q.pageSize(),
                                    q.selected(),
                                    q.recordId(),
                                    r.config().formId(),
                                    q.formValues(),
                                    q.creating(),
                                    q.detailRecordId()),
                            actor);
                });
    }

    public Map<String, Object> formFill(TaskEntries.FormFill command, long actor) {
        return invocations.execute(
                command.entry(),
                actor,
                true,
                r -> {
                    FormFills.Query q = command.query();
                    if (q == null) throw invalid("缺少关联填充来源");
                    invocations.requireTarget(r, q.applicationId(), q.objectId());
                    return records.formFill(
                            new FormFills.Query(
                                    q.applicationId(),
                                    q.objectId(),
                                    r.config().formId(),
                                    q.sourceFieldId(),
                                    q.selectedId(),
                                    q.detailId(),
                                    q.recordId()),
                            actor);
                });
    }

    /** 入口表单的规则求值：formId 固定取入口配置，规则取入口应用的固定版本。 */
    public FieldRules.Evaluation fieldRules(TaskEntries.FieldRules command, long actor) {
        return invocations.execute(
                command.entry(),
                actor,
                true,
                r -> {
                    var q = command.query();
                    if (q == null) throw invalid("缺少求值请求");
                    invocations.requireTarget(r, q.applicationId(), q.objectId());
                    if (q.recordId() != null) invocations.requireList(r);
                    return records.evaluateRules(
                            new FieldRules.EvaluateQuery(
                                    q.applicationId(),
                                    q.objectId(),
                                    r.config().formId(),
                                    q.recordId(),
                                    q.creating(),
                                    q.values(),
                                    q.changed(),
                                    q.overridable(),
                                    q.details()),
                            actor);
                });
    }

    public ApplicationRecords.SaveReceipt receipt(TaskEntries.Receipt command, long actor) {
        return invocations.execute(
                command.entry(),
                actor,
                true,
                r -> {
                    ApplicationRecords.SaveReceipt receipt =
                            records.receipt(
                                    command.entry().applicationId(),
                                    r.config().objectId(),
                                    draftAccess.requestKey(r.resource().id(), command.requestKey()),
                                    actor);
                    return new ApplicationRecords.SaveReceipt(
                            command.requestKey(),
                            receipt.status(),
                            receipt.operationId(),
                            receipt.recordId(),
                            receipt.revision(),
                            receipt.policyVersion(),
                            receipt.result());
                });
    }

    public TaskEntries.Activities activity(TaskEntries.ActivityQuery command, long actor) {
        return invocations.execute(
                command.entry(),
                actor,
                true,
                r -> {
                    long before;
                    try {
                        before =
                                command.before() == null
                                        ? Long.MAX_VALUE
                                        : Long.parseLong(command.before());
                    } catch (NumberFormatException ex) {
                        throw invalid("操作记录游标无效");
                    }
                    if (before < 1) throw invalid("操作记录游标无效");
                    ApplicationRuntimePolicy.Access access =
                            runtimePolicy.access(
                                    r.invocation().applicationId(),
                                    objects.getPublished(r.config().objectId()),
                                    actor);
                    List<TaskEntries.Activity> items = new ArrayList<>();
                    String next = null;
                    List<String> rows =
                            history.taskEvents(
                                    r.invocation().applicationId(),
                                    r.config().objectId(),
                                    r.resource().id(),
                                    Long.toString(actor),
                                    before);
                    for (String raw : rows) {
                        com.fasterxml.jackson.databind.JsonNode event;
                        try {
                            event = json.readTree(raw);
                        } catch (Exception ex) {
                            throw invalid("操作来源无法读取");
                        }
                        next = event.path("id").asText();
                        boolean allowed = true;
                        Set<String> readable = null;
                        for (String state : List.of("before", "after")) {
                            com.fasterxml.jackson.databind.JsonNode row = event.path(state);
                            if (row.isObject()) {
                                ApplicationAuthorization.Capabilities caps =
                                        access.forRow(
                                                row.path("recordCreator").asText(),
                                                json.convertValue(row.path("values"), Map.class));
                                if (!caps.actions().contains(ApplicationActionEnum.READ.getCode()))
                                    allowed = false;
                                if (readable == null) readable = new HashSet<>(caps.readFields());
                                else readable.retainAll(caps.readFields());
                            }
                        }
                        if (allowed)
                            items.add(
                                    new TaskEntries.Activity(
                                            next,
                                            event.path("time").asText(),
                                            event.path("operation").asText(),
                                            r.resource().name()
                                                    + (event.path("related").asBoolean(false)
                                                            ? " · 关联数据更新"
                                                            : ""),
                                            r.release().application().name(),
                                            r.invocation().applicationId(),
                                            r.resource().id(),
                                            r.config().objectId(),
                                            event.path("recordId").asText(),
                                            activityTitle(
                                                    event,
                                                    access.definition().titleFieldId(),
                                                    readable)));
                        if (items.size() == 20) break;
                    }
                    return new TaskEntries.Activities(
                            items, rows.size() < 100 && items.size() < 20 ? null : next);
                });
    }

    String activityTitle(
            com.fasterxml.jackson.databind.JsonNode event, String field, Set<String> readable) {
        com.fasterxml.jackson.databind.JsonNode row =
                event.path("after").isObject() ? event.path("after") : event.path("before");
        if (field != null && readable != null && readable.contains(field)) {
            com.fasterxml.jackson.databind.JsonNode value = row.path("values").path(field);
            if (value.isValueNode() && !value.isNull() && !value.asText().isBlank()) {
                String text = value.asText();
                return text.substring(0, Math.min(text.length(), 160));
            }
        }
        return "记录 " + event.path("recordId").asText();
    }
}
