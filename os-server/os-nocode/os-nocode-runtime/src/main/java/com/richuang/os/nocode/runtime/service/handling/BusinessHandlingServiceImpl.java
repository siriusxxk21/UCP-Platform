package com.richuang.os.nocode.runtime.service.handling;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import cn.hutool.crypto.digest.DigestUtil;

import com.fasterxml.jackson.databind.*;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.module.bpm.api.event.*;
import com.richuang.os.module.bpm.api.task.*;
import com.richuang.os.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.work.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.published.ApplicationPublishedService;
import com.richuang.os.nocode.application.service.published.ApplicationVersionContext;
import com.richuang.os.nocode.application.service.resource.ApplicationProcessDefinition;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.form.BusinessHandlingPolicies;
import com.richuang.os.nocode.runtime.dal.dataobject.HandlingRequestDO;
import com.richuang.os.nocode.runtime.dal.dataobject.TaskWorkRecordDO;
import com.richuang.os.nocode.runtime.dal.mapper.HandlingRequestMapper;
import com.richuang.os.nocode.runtime.dal.mapper.RecordHistoryMapper;
import com.richuang.os.nocode.runtime.dal.mapper.TaskCenterMapper;
import com.richuang.os.nocode.runtime.dal.mapper.TaskWorkEntryMapper;
import com.richuang.os.nocode.runtime.service.record.DocumentReceipts;
import com.richuang.os.nocode.runtime.service.record.RecordService;
import com.richuang.os.nocode.runtime.service.task.*;
import com.richuang.os.nocode.work.service.draft.WorkDraftService;
import com.richuang.os.nocode.work.service.submission.WorkSubmissionService;

import jakarta.annotation.*;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

import java.util.*;

/** 引擎终态先持久登记，提交后另启业务事务；业务失败不回滚已发生的审批事实。 */
@Service
public class BusinessHandlingServiceImpl
        implements BusinessHandlingService, ApplicationListener<BpmProcessInstanceStatusEvent> {
    @Resource private HandlingRequestMapper requests;

    @Resource(name = "nocodeRecordService")
    private RecordService records;

    @Resource private ApplicationService applications;
    @Resource private ApplicationPublishedService published;
    @Resource private ApplicationVersionContext versions;
    @Resource private TaskCenterMapper taskBindings;
    @Resource private TaskWorkEntryMapper taskContributions;
    @Resource private RecordHistoryMapper recordHistory;
    @Resource private ApplicationProcessDefinition definitions;
    @Resource private DataObjectApi objects;
    @Resource private DocumentReceipts receipts;
    @Resource private WorkDraftService drafts;
    @Resource private WorkSubmissionService submissions;
    @Resource private TaskEntryRuntimeScope taskScope;
    @Resource private ObjectProvider<TaskEntryRuntimeService> entries;
    @Resource private BpmProcessInstanceApi instances;
    @Resource private BpmProcessTaskApi tasks;
    @Resource private PlatformTransactionManager manager;
    @Resource private ObjectMapper json;

    @Value("${nocode.handling.reconcile-enabled:true}")
    private boolean reconcileEnabled;

    private ObjectMapper canonical;
    private TransactionTemplate tx;
    private TransactionTemplate independent;

    @PostConstruct
    void initialize() {
        canonical = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        tx = new TransactionTemplate(manager);
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public BusinessHandling.Result submit(ApplicationRecords.Save command, long actor) {
        return tx.execute(
                status -> {
                    if (command == null
                            || command.requestKey() == null
                            || !command.requestKey().matches("[A-Za-z0-9:_-]{8,128}"))
                        throw invalid("提交必须携带有效请求标识");
                    var model = records.model(command.applicationId(), command.objectId(), actor);
                    if (command.relatedRecords() != null && !command.relatedRecords().isEmpty())
                        return new BusinessHandling.Result(
                                HandlingOutcomeEnum.EFFECTIVE.getCode(),
                                records.save(command, actor),
                                null);
                    requests.lockCommand(
                            actor
                                    + ":"
                                    + command.applicationId()
                                    + ":"
                                    + command.objectId()
                                    + ":"
                                    + command.requestKey());
                    var previous =
                            requests.byCommand(
                                    Long.toString(actor),
                                    command.applicationId(),
                                    command.objectId(),
                                    command.requestKey());
                    if (previous != null) {
                        if (!previous.getRequestDigest().equals(receipts.commandDigest(command)))
                            throw invalid("同一个请求标识不能用于不同的申请内容");
                        return submitted(previous);
                    }
                    receipts.successful(command, actor);
                    var direct =
                            records.receipt(
                                    command.applicationId(),
                                    command.objectId(),
                                    command.requestKey(),
                                    actor);
                    if (DocumentReceiptStatusEnum.SUCCEEDED.matches(direct.status()))
                        return new BusinessHandling.Result(
                                HandlingOutcomeEnum.EFFECTIVE.getCode(), direct.result(), null);
                    var preview = records.prepareHandling(command, actor);
                    var current = objects.getPublished(command.objectId());
                    if (!BusinessHandlingPolicies.required(
                            current, command.id() == null, preview.record().values()))
                        return new BusinessHandling.Result(
                                HandlingOutcomeEnum.EFFECTIVE.getCode(),
                                records.save(command, actor),
                                null);
                    var rule = BusinessHandlingPolicies.rule(current, command.id() == null);
                    // 总任务委托不伪造成独立 TASK_ENTRY；审批重新授权链尚未接入前明确拒绝，不能留下无法生效的申请。
                    if (taskScope.delegated())
                        throw invalid("该资源启用了业务审批，暂不支持通过总任务统一授权提交，请使用原业务审批入口");
                    var process = definitions.require(rule.processDefinitionId());
                    var release = applications.published(command.applicationId());
                    var invocation = taskScope.current();
                    var id = UUID.randomUUID().toString();
                    var source = new WorkSourceRef(WorkSourceEnum.BUSINESS_APPROVAL, id);
                    var resource =
                            new PublishedResourceRef(
                                    command.applicationId(),
                                    release.versionNo(),
                                    release.checksum(),
                                    command.formId(),
                                    ApplicationResourceKindEnum.FORM.getCode());
                    var visible = visible(preview, model.object());
                    var draft =
                            drafts.save(
                                    new WorkDrafts.Save(
                                            null,
                                            null,
                                            resource,
                                            command.objectId(),
                                            command.id(),
                                            command.expectedRevision(),
                                            visible.record().values(),
                                            visible.details()),
                                    actor,
                                    source);
                    var material =
                            new WorkDrafts.Submission(
                                    UUID.randomUUID().toString(),
                                    draft.id(),
                                    resource,
                                    command.objectId(),
                                    command.id(),
                                    command.expectedRevision(),
                                    visible.record().values(),
                                    null,
                                    visible.details(),
                                    visible.record().displayValues(),
                                    receipts.policyVersion(current),
                                    new BusinessHandling.Material(
                                            command,
                                            command.id() == null
                                                    ? null
                                                    : records.get(
                                                            command.applicationId(),
                                                            command.objectId(),
                                                            command.id(),
                                                            actor),
                                            visible.relations()));
                    material =
                            submissions.create(
                                    new WorkDrafts.Submit(
                                            draft.id(), draft.revision(), "handling:" + id),
                                    material,
                                    actor);
                    drafts.markSubmitted(draft.id(), draft.revision(), actor, source);
                    var row = new HandlingRequestDO();
                    row.setId(id);
                    row.setApplicationId(Long.valueOf(command.applicationId()));
                    row.setApplicationName(release.application().name());
                    row.setApplicationVersion(release.versionNo());
                    row.setObjectId(Long.valueOf(command.objectId()));
                    row.setObjectName(current.objectName());
                    row.setEntryId(invocation == null ? null : invocation.entryId());
                    row.setRecordId(command.id());
                    row.setOperation(
                            (command.id() == null
                                            ? ApplicationActionEnum.CREATE
                                            : ApplicationActionEnum.UPDATE)
                                    .getCode());
                    row.setName(
                            (invocation == null ? current.objectName() : invocation.name())
                                    + (command.id() == null ? " · 新建申请" : " · 变更申请"));
                    row.setRequestKey(command.requestKey());
                    row.setRequestDigest(receipts.commandDigest(command));
                    row.setDefinitionChecksum(hash(current));
                    row.setDefinitionJson(encode(model.object()));
                    row.setSubmissionId(material.id());
                    row.setProcessDefinitionId(process.getId());
                    row.setProcessDefinitionKey(process.getKey());
                    row.setStatus(HandlingStateEnum.PENDING.getCode());
                    requests.create(row, Long.toString(actor));
                    var request = new BpmProcessInstanceCreateReqDTO();
                    request.setBusinessKey("nocode-handling:" + id);
                    request.setProcessDefinitionId(process.getId());
                    Map<String, Object> variables = new LinkedHashMap<>();
                    rule.variables()
                            .forEach(
                                    (key, field) -> {
                                        if (!preview.record()
                                                .permissions()
                                                .readFields()
                                                .contains(field)) throw invalid("没有读取流程所需字段的权限");
                                        variables.put(key, preview.record().values().get(field));
                                    });
                    request.setVariables(variables);
                    String instance = instances.createProcessInstance(actor, request);
                    if (instance == null
                            || instance.isBlank()
                            || requests.attach(id, instance) != 1) throw invalid("申请与流程关联失败");
                    return submitted(requests.lock(id));
                });
    }

    @Override
    public BusinessHandling.Result receipt(BusinessHandling.Receipt query, long actor) {
        return tx.execute(
                status -> {
                    records.model(query.applicationId(), query.objectId(), actor);
                    var direct =
                            records.receipt(
                                    query.applicationId(),
                                    query.objectId(),
                                    query.requestKey(),
                                    actor);
                    if (DocumentReceiptStatusEnum.SUCCEEDED.matches(direct.status()))
                        return new BusinessHandling.Result(
                                HandlingOutcomeEnum.EFFECTIVE.getCode(), direct.result(), null);
                    var row =
                            requests.byCommand(
                                    Long.toString(actor),
                                    query.applicationId(),
                                    query.objectId(),
                                    query.requestKey());
                    return row == null ? null : submitted(row);
                });
    }

    @Override
    public PageResult<BusinessHandling.Request> mine(BusinessHandling.Query query, long actor) {
        if (actor <= 0
                || query == null
                || query.pageNo() < 1
                || query.pageNo() > 100000
                || query.pageSize() < 1
                || query.pageSize() > 100) throw invalid("申请分页参数无效");
        if (query.status() != null) HandlingStateEnum.fromCode(query.status());
        if (query.applicationId() != null && !query.applicationId().matches("[1-9][0-9]{0,18}"))
            throw invalid("应用编号无效");
        return new PageResult<>(
                requests
                        .mine(
                                Long.toString(actor),
                                query.status(),
                                query.applicationId(),
                                query.pageSize(),
                                (query.pageNo() - 1) * query.pageSize())
                        .stream()
                        .map(this::view)
                        .toList(),
                requests.countMine(Long.toString(actor), query.status(), query.applicationId()));
    }

    @Override
    public BusinessHandling.Detail detail(String id, String taskId, long actor) {
        return tx.execute(
                status -> {
                    var row = require(id);
                    if (!row.getCreator().equals(Long.toString(actor))) {
                        if (taskId == null) {
                            var context =
                                    tasks.materialReviewContext(
                                            actor, row.getProcessInstanceId(), null);
                            if (context == null
                                    || !Objects.equals(
                                            context.processInstanceId(), row.getProcessInstanceId())
                                    || !Objects.equals(
                                            context.processDefinitionId(),
                                            row.getProcessDefinitionId()))
                                throw invalid("无权读取申请材料");
                        } else {
                            var task = tasks.getAssignedTask(actor, taskId);
                            if (task == null
                                    || !Objects.equals(
                                            task.processInstanceId(), row.getProcessInstanceId())
                                    || !Objects.equals(
                                            task.processDefinitionId(),
                                            row.getProcessDefinitionId()))
                                throw invalid("申请材料不属于当前待办");
                        }
                    }
                    // 个人申请及当前已分配审批任务仅获得当时提交的可读材料，不得到应用数据写权限。
                    return new BusinessHandling.Detail(
                            view(row),
                            submissions.get(
                                    row.getSubmissionId(), Long.parseLong(row.getCreator())),
                            decode(row.getDefinitionJson(), DataCenter.Definition.class));
                });
    }

    @Override
    public BusinessHandling.Request withdraw(BusinessHandling.Withdraw command, long actor) {
        return tx.execute(
                status -> {
                    var row = owned(command.id(), actor);
                    revision(row, command.expectedRevision());
                    if (command.reason() == null
                            || command.reason().isBlank()
                            || command.reason().length() > 500) throw invalid("请填写 1 至 500 字撤回原因");
                    if (HandlingStateEnum.APPLY_FAILED.matches(row.getStatus())) {
                        // 已审批但未生效的申请可放弃业务写入；保留流程原终态及申请失败说明。
                        transition(
                                row,
                                HandlingStateEnum.CANCELED,
                                null,
                                "审批通过后放弃生效：" + command.reason(),
                                actor);
                    } else {
                        if (!HandlingStateEnum.PENDING.matches(row.getStatus()))
                            throw invalid("当前申请不能撤回");
                        instances.cancelByStarter(
                                actor, row.getProcessInstanceId(), command.reason());
                        if (HandlingStateEnum.PENDING.matches(
                                requests.lock(row.getId()).getStatus()))
                            throw invalid("流程撤回状态尚未确认");
                    }
                    return view(requests.lock(row.getId()));
                });
    }

    @Override
    public BusinessHandling.Reopen reopen(String id, long actor) {
        return tx.execute(
                status -> {
                    var row = owned(id, actor);
                    if (!HandlingStateEnum.REJECTED.matches(row.getStatus())
                            && !HandlingStateEnum.CANCELED.matches(row.getStatus()))
                        throw invalid("仅已驳回或已撤回的申请可以修改后重新提交");
                    if (versions.version(row.getApplicationId().toString()) == null
                            && taskBindings.handlingTaskId(id, actor) != null)
                        throw invalid("该申请关联执行任务，请在原任务中修改并重新提交");
                    var material = submissions.get(row.getSubmissionId(), actor);
                    var intent = material.handling().intent();
                    // 任务内恢复使用可信固定版本；独立申请入口继续校验当前发布版本。
                    ApplicationCenter.Published release =
                            applications.published(row.getApplicationId().toString());
                    if (release.versionNo() != row.getApplicationVersion())
                        throw invalid("应用版本已变化，请查看原申请材料并从当前入口重新填写");
                    TaskEntries.Locator entry =
                            row.getEntryId() == null
                                    ? null
                                    : new TaskEntries.Locator(
                                            intent.applicationId(),
                                            row.getEntryId(),
                                            row.getApplicationVersion());
                    var context = entry == null ? null : entries.getObject().context(entry, actor);
                    var model =
                            context == null
                                    ? records.model(
                                            intent.applicationId(), intent.objectId(), actor)
                                    : context.model();
                    var current =
                            intent.id() == null
                                    ? null
                                    : entry == null
                                            ? records.get(
                                                    intent.applicationId(),
                                                    intent.objectId(),
                                                    intent.id(),
                                                    actor)
                                            : entries.getObject()
                                                    .get(
                                                            new TaskEntries.Get(entry, intent.id()),
                                                            actor);
                    var caps =
                            current == null ? model.permissions() : current.record().permissions();
                    var action =
                            intent.id() == null
                                    ? ApplicationActionEnum.CREATE
                                    : ApplicationActionEnum.UPDATE;
                    if (!caps.actions().contains(action.getCode())) throw invalid("当前已无此申请的业务写入权限");
                    if (current != null
                            && !Objects.equals(
                                    current.record().revision(), intent.expectedRevision()))
                        throw invalid("原业务记录已变化，请从业务列表查看最新数据后重新发起");
                    var resources =
                            context == null
                                    ? release.definition().resources()
                                    : context.resources();
                    var form =
                            intent.formId() == null
                                    ? null
                                    : resources.stream()
                                            .filter(r -> r.id().equals(intent.formId()))
                                            .map(
                                                    r ->
                                                            json.convertValue(
                                                                    r.config(),
                                                                    ApplicationUi.Form.class))
                                            .findFirst()
                                            .orElseThrow(() -> invalid("原表单已不可用"));
                    Map<String, List<String>> links = new LinkedHashMap<>();
                    if (material.handling().before() != null)
                        links.putAll(material.handling().before().relations());
                    if (intent.relations() != null) links.putAll(intent.relations());
                    links.putAll(material.handling().relations());
                    var initial =
                            new ApplicationRecords.Aggregate(
                                    new ApplicationRecords.Row(
                                            intent.id(),
                                            intent.expectedRevision(),
                                            material.values(),
                                            caps,
                                            material.displayValues()),
                                    material.details(),
                                    List.of(),
                                    links);
                    return new BusinessHandling.Reopen(
                            model,
                            form,
                            HandlingMaterials.restore(
                                    initial,
                                    model.object(),
                                    objects.getPublished(intent.objectId())),
                            context,
                            intent.formId());
                });
    }

    @Override
    public BusinessHandling.Request retry(BusinessHandling.Retry command, long actor) {
        tx.executeWithoutResult(
                status -> {
                    var row = owned(command.id(), actor);
                    revision(row, command.expectedRevision());
                    if (!HandlingStateEnum.APPLY_FAILED.matches(row.getStatus()))
                        throw invalid("仅生效失败的申请可以重试");
                    transition(row, HandlingStateEnum.APPLY_PENDING, null, null, actor);
                    afterCommit(row.getId());
                });
        return tx.execute(status -> view(owned(command.id(), actor)));
    }

    @Override
    public void onApplicationEvent(BpmProcessInstanceStatusEvent event) {
        if (event.getBusinessKey() == null
                || !event.getBusinessKey().matches("nocode-handling:[a-f0-9-]{36}")
                || event.getId() == null
                || event.getStatus() == null) return;
        var state =
                switch (event.getStatus()) {
                    case BpmProcessInstanceStatus.APPROVED -> HandlingStateEnum.APPLY_PENDING;
                    case BpmProcessInstanceStatus.REJECTED -> HandlingStateEnum.REJECTED;
                    case BpmProcessInstanceStatus.CANCELED -> HandlingStateEnum.CANCELED;
                    default -> null;
                };
        if (state == null) return;
        tx.executeWithoutResult(
                status -> {
                    var row =
                            requests.lock(
                                    event.getBusinessKey().substring("nocode-handling:".length()));
                    if (row == null
                            || !HandlingStateEnum.PENDING.matches(row.getStatus())
                            || !Objects.equals(
                                    row.getProcessDefinitionKey(), event.getProcessDefinitionKey())
                            || row.getProcessInstanceId() != null
                                    && !row.getProcessInstanceId().equals(event.getId())) return;
                    if (requests.attach(row.getId(), event.getId()) != 1) throw invalid("流程实例不匹配");
                    transition(row, state, null, null, Long.parseLong(row.getCreator()));
                    if (state == HandlingStateEnum.APPLY_PENDING) afterCommit(row.getId());
                });
    }

    private void afterCommit(String id) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        apply(id);
                    }
                });
    }

    @Scheduled(fixedDelayString = "${nocode.handling.reconcile-delay:30000}")
    public void scheduledReconcile() {
        if (reconcileEnabled) reconcile();
    }

    @Override
    public void reconcile() {
        requests.pendingApplication().forEach(this::apply);
    }

    private void apply(String id) {
        try {
            independent.executeWithoutResult(
                    status -> {
                        HandlingRequestDO row = require(id);
                        if (!HandlingStateEnum.APPLY_PENDING.matches(row.getStatus())) return;
                        long actor = Long.parseLong(row.getCreator());
                        WorkDrafts.Submission material =
                                submissions.get(row.getSubmissionId(), actor);
                        ApplicationRecords.Save command = material.handling().intent();
                        long historyCheckpoint = recordHistory.transactionCheckpoint();
                        // 仅仍被任务固定引用的申请使用历史版本，普通入口申请继续执行当前版本保护。
                        ApplicationRecords.Aggregate result =
                                taskBindings.ownsHandlingRequest(
                                                id, material.resource(), command.objectId(), actor)
                                        ? published.withVersion(
                                                material.resource(),
                                                () -> applyMaterial(row, material, command, actor))
                                        : applyMaterial(row, material, command, actor);
                        // 与业务生效同事务封存该次批准的实际材料，后续共享编辑不能改写本次贡献。
                        taskContributions.applied(
                                id,
                                encode(result),
                                result.record().revision(),
                                Long.toString(actor));
                        for (TaskWorkRecordDO contribution :
                                taskContributions.requestRecords(id, Long.toString(actor))) {
                            int changed =
                                    recordHistory.bindTaskContribution(
                                            historyCheckpoint,
                                            command.applicationId(),
                                            command.objectId(),
                                            result.record().id(),
                                            Long.toString(actor),
                                            contribution.getId());
                            if (changed == 0)
                                taskContributions.unchanged(
                                        contribution.getId(), Long.toString(actor));
                        }
                        transition(
                                row, HandlingStateEnum.APPROVED, result.record().id(), null, actor);
                    });
        } catch (RuntimeException error) {
            independent.executeWithoutResult(
                    status -> {
                        var row = require(id);
                        if (HandlingStateEnum.APPLY_PENDING.matches(row.getStatus())) {
                            String message =
                                    error instanceof ServiceException
                                            ? error.getMessage()
                                            : "业务写入暂时失败，请重试或联系管理员";
                            transition(
                                    row,
                                    HandlingStateEnum.APPLY_FAILED,
                                    null,
                                    message == null
                                            ? "业务生效失败"
                                            : message.substring(0, Math.min(900, message.length())),
                                    Long.parseLong(row.getCreator()));
                        }
                    });
        }
    }

    private ApplicationRecords.Aggregate applyMaterial(
            HandlingRequestDO row,
            WorkDrafts.Submission material,
            ApplicationRecords.Save command,
            long actor) {
        ApplicationCenter.Published release = applications.published(command.applicationId());
        if (release.versionNo() != material.resource().applicationVersion()
                || !release.checksum().equals(material.resource().applicationChecksum())
                || !hash(objects.getPublished(command.objectId()))
                        .equals(row.getDefinitionChecksum()))
            throw invalid("应用或对象配置已变化，请放弃本次生效并重新提交审批");
        return HandlingWriteScope.execute(
                row.getId(),
                command,
                material,
                actor,
                () -> {
                    if (row.getEntryId() != null)
                        return entries.getObject()
                                .applyHandling(
                                        new TaskEntries.Locator(
                                                command.applicationId(),
                                                row.getEntryId(),
                                                row.getApplicationVersion()),
                                        command,
                                        material,
                                        actor);
                    assertUnchanged(records.prepareHandling(command, actor), material);
                    return records.save(command, actor);
                });
    }

    /** 重新校验后的用户可读内容必须和审批材料一致，默认值或关联变化不能悄悄换成另一份申请。 */
    public static void assertUnchanged(
            ApplicationRecords.Aggregate candidate, WorkDrafts.Submission material) {
        for (var field : material.values().entrySet())
            if (!same(candidate.record().values().get(field.getKey()), field.getValue()))
                throw invalid("申请内容的默认值或计算结果已变化，请重新提交审批");
        for (var group : material.details().entrySet()) {
            var current = candidate.details().get(group.getKey());
            if (current == null || current.size() != group.getValue().size())
                throw invalid("申请明细已变化，请重新提交审批");
            for (int i = 0; i < current.size(); i++)
                if (!same(current.get(i).values(), group.getValue().get(i).values()))
                    throw invalid("申请明细已变化，请重新提交审批");
        }
    }

    private static boolean same(Object left, Object right) {
        if ((left instanceof Number || right instanceof Number)
                && (left instanceof Number || left instanceof String)
                && (right instanceof Number || right instanceof String)) {
            try {
                return new java.math.BigDecimal(left.toString())
                                .compareTo(new java.math.BigDecimal(right.toString()))
                        == 0;
            } catch (NumberFormatException invalidNumber) {
                return false;
            }
        }
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b)
            return a.keySet().equals(b.keySet())
                    && a.keySet().stream().allMatch(k -> same(a.get(k), b.get(k)));
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!same(a.get(i), b.get(i))) return false;
            return true;
        }
        return Objects.equals(left, right);
    }

    private ApplicationRecords.Aggregate visible(
            ApplicationRecords.Aggregate input, DataCenter.Definition definition) {
        Map<String, Object> values = new LinkedHashMap<>();
        definition
                .fields()
                .forEach(
                        f -> {
                            // 自动编号在业务生效时分配，申请阶段不展示保存点内的临时编号。
                            if (!FieldTypeEnum.AUTO_NUMBER.matches(f.type())
                                    && input.record().permissions().readFields().contains(f.id())
                                    && input.record().values().containsKey(f.id()))
                                values.put(
                                        f.id(), materialValue(input.record().values().get(f.id())));
                        });
        Map<String, List<ApplicationRecords.Row>> details = new LinkedHashMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        input.record()
                .displayValues()
                .forEach(
                        (key, value) -> {
                            if (values.containsKey(key)
                                    || definition.relations().stream()
                                            .anyMatch(
                                                    r ->
                                                            ("relation:" + r.id()).equals(key)
                                                                    && input.record()
                                                                            .permissions()
                                                                            .readRelations()
                                                                            .contains(r.id())))
                                display.put(key, value);
                        });
        definition
                .details()
                .forEach(
                        d -> {
                            if (input.record().permissions().readDetails().contains(d.id())
                                    && input.details().containsKey(d.id()))
                                details.put(
                                        d.id(),
                                        input.details().get(d.id()).stream()
                                                .map(
                                                        row -> {
                                                            Map<String, Object> fields =
                                                                    new LinkedHashMap<>();
                                                            row.values()
                                                                    .forEach(
                                                                            (key, value) ->
                                                                                    fields.put(
                                                                                            key,
                                                                                            materialValue(
                                                                                                    value)));
                                                            return new ApplicationRecords.Row(
                                                                    row.id(),
                                                                    row.revision(),
                                                                    fields,
                                                                    row.permissions(),
                                                                    row.displayValues(),
                                                                    row.clientRowKey());
                                                        })
                                                .toList());
                        });
        return new ApplicationRecords.Aggregate(
                new ApplicationRecords.Row(
                        input.record().id(), input.record().revision(), values, null, display),
                details,
                List.of(),
                input.relations());
    }

    /** 与业务记录协议一致，数值以文本封存，避免 JSON 读取与浏览器丢失大整数/小数精度。 */
    private Object materialValue(Object value) {
        return value instanceof java.math.BigDecimal decimal
                ? decimal.toPlainString()
                : value instanceof Number ? value.toString() : value;
    }

    private HandlingRequestDO require(String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw invalid("申请不存在");
        var row = requests.lock(id);
        if (row == null) throw invalid("申请不存在");
        return row;
    }

    private HandlingRequestDO owned(String id, long actor) {
        var row = require(id);
        if (!row.getCreator().equals(Long.toString(actor))) throw invalid("无权办理此申请");
        return row;
    }

    private ServiceException conflict() {
        return new ServiceException(CONFLICT, "申请状态已变化，请刷新后重试");
    }

    private void revision(HandlingRequestDO row, int revision) {
        if (row.getLockVersion() != revision) throw conflict();
    }

    private void transition(
            HandlingRequestDO row,
            HandlingStateEnum state,
            String record,
            String error,
            long actor) {
        if (requests.transition(
                        row.getId(),
                        row.getLockVersion(),
                        state.getCode(),
                        record,
                        error,
                        Long.toString(actor))
                != 1) throw conflict();
    }

    private BusinessHandling.Result submitted(HandlingRequestDO row) {
        return new BusinessHandling.Result(
                HandlingOutcomeEnum.SUBMITTED.getCode(), null, view(row));
    }

    private BusinessHandling.Request view(HandlingRequestDO r) {
        return new BusinessHandling.Request(
                r.getId(),
                r.getLockVersion(),
                r.getStatus(),
                r.getApplicationId().toString(),
                r.getApplicationName(),
                r.getObjectId().toString(),
                r.getObjectName(),
                r.getEntryId(),
                r.getRecordId(),
                r.getOperation(),
                r.getName(),
                r.getProcessInstanceId(),
                r.getProcessDefinitionId(),
                r.getSubmissionId(),
                r.getCreateTime(),
                r.getUpdateTime(),
                r.getError());
    }

    private String encode(Object value) {
        try {
            return canonical.writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw invalid("申请材料无法编码");
        }
    }

    private String hash(Object value) {
        return DigestUtil.sha256Hex(encode(value));
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (java.io.IOException e) {
            throw invalid("申请材料无法读取");
        }
    }
}
