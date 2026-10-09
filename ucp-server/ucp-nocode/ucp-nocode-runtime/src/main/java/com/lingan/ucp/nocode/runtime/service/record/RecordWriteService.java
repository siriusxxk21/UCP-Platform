package com.lingan.ucp.nocode.runtime.service.record;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.form.DocumentPolicies;
import com.lingan.ucp.nocode.metadata.service.formula.Calculations;
import com.lingan.ucp.nocode.metadata.service.formula.FieldExpressions;
import com.lingan.ucp.nocode.runtime.dal.mapper.*;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.application.ApplicationBusinessRules;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizFileBindingService;
import com.lingan.ucp.nocode.runtime.service.rules.FieldRuleEnforcer;
import com.lingan.ucp.nocode.runtime.service.rules.RuleContext;
import com.lingan.ucp.nocode.runtime.service.selection.RecordDirectoryValues;
import com.lingan.ucp.nocode.runtime.service.selection.SelectionCatalog;

import jakarta.annotation.Resource;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 主记录与整单写入编排，统一导入、工作草稿和业务动作入口。保留写前写后校验、幂等收据和成功历史的原顺序。 */
@Component
public class RecordWriteService {
    @Resource private com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeScope taskScope;
    @Resource private RecordContextResolver contexts;
    @Resource private RecordDetailWriter details;
    @Resource private RecordDocumentValidation documents;
    @Resource private RecordPersistence persistence;
    @Resource private RecordQueryService queries;
    @Resource private RecordReadService reader;
    @Resource private RecordRelationAccess relationAccess;
    @Resource private RecordSelectionSupport selections;
    @Resource private RecordTransactions transactions;
    @Resource private RecordAutomations automations;
    @Resource private RecordLinkageSync linkageSync;
    @Resource private RecordCaptures captures;
    @Resource private ApplicationService applications;

    @Resource
    private com.lingan.ucp.nocode.application.service.resource.ApplicationResourceValidator
            resourceValidator;

    @Resource private ApplicationRuntimePolicy policy;
    @Resource private ApplicationBusinessRules businessRules;
    @Resource private RecordProcessService processes;
    @Resource private DataObjectApi objects;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordValues values;
    @Resource private RecordAutoNumbers autoNumbers;
    @Resource private RecordCalculations calculations;
    @Resource private OrderedRecordCalculations ordered;
    @Resource private RecordDirectoryValues directoryValues;
    @Resource private SelectionCatalog selectionCatalog;
    @Resource private RecordMapper records;
    @Resource private FieldRuleEnforcer ruleEnforcer;
    @Resource private DetailPositions detailPositions;
    @Resource private com.lingan.ucp.nocode.runtime.service.history.RecordHistoryTracker history;
    @Resource private com.lingan.ucp.nocode.runtime.service.record.DocumentReceipts receipts;
    @Resource private PlatformTransactionManager manager;
    @Resource private BizFileBindingService bizFiles;

    @Resource
    private org.springframework.beans.factory.ObjectProvider<RelatedFormService> relatedForms;

    @Resource private com.lingan.ucp.nocode.runtime.service.live.RecordChangeCollector changes;

    /** 首版导入新增主记录，整批同一事务。任何一行失败都回滚，用户修正原文件重试不会产生半批重复。 */
    public int importRecords(
            String app, String object, List<Map<String, Object>> rows, long actor) {
        return importRecords(app, object, rows, actor, null);
    }

    public int importRecords(
            String app,
            String object,
            List<Map<String, Object>> rows,
            long actor,
            Context context) {
        return transactions.tx(
                () -> {
                    if (rows == null || rows.isEmpty() || rows.size() > 500)
                        throw invalid("导入需要 1 到 500 行数据");
                    automations.lock(object);
                    linkageSync.lock(object);
                    ordered.lock(object);
                    ApplicationRecords.Model model = queries.importModel(app, object, actor);
                    DataCenter.Definition definition = contexts.definition(app, object, actor);
                    OrderedImportBatch batch =
                            canBatchOrderedImport(app, definition, actor, context)
                                    ? new OrderedImportBatch()
                                    : null;
                    for (int index = 0; index < rows.size(); index++) {
                        try {
                            if (!model.permissions()
                                    .writeFields()
                                    .containsAll(rows.get(index).keySet()))
                                throw invalid("包含无权导入的字段");
                            save(
                                    new Save(
                                            app,
                                            object,
                                            null,
                                            null,
                                            rows.get(index),
                                            null,
                                            null,
                                            context),
                                    actor,
                                    true,
                                    false,
                                    batch);
                        } catch (ServiceException e) {
                            throw invalid(
                                    "第 " + (index + 2) + " 行：" + e.getMessage() + "；本批未写入，请修正后重试");
                        }
                    }
                    if (batch != null) {
                        ordered.completeImported(app, definition, batch.ids(), actor);
                        batch.finish();
                    }
                    return rows.size();
                });
    }

    /** 仅无中间计算消费者的纯新增导入启用；所有其他路径保留逐行显式完成点。 */
    private boolean canBatchOrderedImport(
            String app, DataCenter.Definition definition, long actor, Context context) {
        DataCenter.Definition current = objects.getPublished(definition.objectId());
        if (context != null
                || ordered.fields(definition).isEmpty()
                || DocumentPolicies.policy(definition) != null
                || DocumentPolicies.policy(current) != null
                || !automations.canDeferOrderedImport(definition.objectId())
                // 参与数据联动自动更新的对象逐行触发回写，保守退出批末计算。
                || linkageSync.participates(definition.objectId())) return false;
        if (policy.effectiveGrants(app, definition, actor).stream()
                .anyMatch(grant -> !grant.actionScopes().isEmpty())) return false;
        for (DataCenter.Definition candidate : List.of(definition, current))
            for (FieldDefinition field : candidate.fields()) {
                DataCenter.FieldOptions options =
                        candidate
                                .fieldOptions()
                                .getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
                if (FieldTypeEnum.SUMMARY.matches(field.type())
                        || options.calculation() != null
                                && !Calculations.orderedStored(options)
                                && !independentImportSnapshot(candidate, options)) return false;
            }
        return true;
    }

    /** 基础来源快照仍逐行保存；仅不消费任何计算字段时允许有序值推迟到批末。 */
    private boolean independentImportSnapshot(
            DataCenter.Definition definition, DataCenter.FieldOptions options) {
        CalculationOptions calculation = options.calculation();
        if (!CalculationUpdateEnum.ON_SAVE.matches(calculation.updateMode())) return false;
        Set<String> dependencies;
        if (CalculationModeEnum.LOCAL.matches(calculation.mode())) {
            Map<String, String> names = new HashMap<>();
            definition.fields().forEach(field -> names.put(field.code(), field.id()));
            dependencies = new HashSet<>();
            for (String code : FieldExpressions.parse(options.expression(), names).references())
                dependencies.add(names.get(code));
        } else if (CalculationModeEnum.STATISTICS.matches(calculation.mode())
                && (calculation.targetObjectId() == null
                        || definition.objectId().equals(calculation.targetObjectId()))) {
            dependencies = Calculations.sourceFields(definition, calculation);
        } else return false;
        return definition.fields().stream()
                .filter(field -> dependencies.contains(field.id()))
                .noneMatch(field -> FieldTypeEnum.fromCode(field.type()).isComputed());
    }

    /** 显式调用栈中的批次，既不注册提交回调，也不在保存点/请求之间保存线程状态。 */
    private static final class OrderedImportBatch {
        private final Map<String, java.util.function.Supplier<Aggregate>> completions =
                new LinkedHashMap<>();

        void add(String id, java.util.function.Supplier<Aggregate> completion) {
            if (completions.putIfAbsent(id, completion) != null) throw invalid("导入记录重复，整批已回滚");
        }

        Set<String> ids() {
            return completions.keySet();
        }

        void finish() {
            int index = 0;
            for (java.util.function.Supplier<Aggregate> completion : completions.values()) {
                try {
                    completion.get();
                } catch (ServiceException error) {
                    throw invalid(
                            "第 " + (index + 2) + " 行：" + error.getMessage() + "；本批未写入，请修正后重试");
                }
                index++;
            }
        }
    }

    /**
     * 保存业务整单，已有记录必须满足预期修订。
     *
     * <p>存在独立关联区域输入时交由关联编排服务统一提交，各对象仍复用本服务的写入校验。 相同请求键按原收据恢复结果，不能重复生成业务记录、成功历史或已办。
     */
    public Aggregate save(Save command, long actor) {
        if (command != null
                && command.relatedRecords() != null
                && !command.relatedRecords().isEmpty())
            return relatedForms.getObject().save(command, actor);
        return save(command, actor, false);
    }

    /** 复用完整的写前校验。保存点回滚默认编号等预备写入，不触发业务写入和成功历史。 */
    public Aggregate prepareHandling(Save command, long actor) {
        return transactions.tx(
                () -> {
                    if (command == null) throw invalid("缺少业务记录");
                    automations.lock(command.objectId());
                    linkageSync.lock(command.objectId());
                    ordered.lock(command.objectId());
                    // 主记录锁属于外层申请事务，不能随预备校验的保存点回滚而释放。
                    // 否则不同请求键可能同时封存同一版本的两份变更申请。
                    if (command.id() != null) {
                        DataCenter.Definition d =
                                contexts.definition(
                                        command.applicationId(), command.objectId(), actor);
                        persistence.authorizedRead(
                                schemas.main(d),
                                command.id(),
                                actor,
                                true,
                                policy.access(command.applicationId(), d, actor),
                                ApplicationActionEnum.UPDATE);
                        processes.requireIdle(command.objectId(), command.id());
                    }
                    TransactionTemplate nested = new TransactionTemplate(manager);
                    nested.setPropagationBehavior(
                            org.springframework.transaction.TransactionDefinition
                                    .PROPAGATION_NESTED);
                    return nested.execute(
                            status -> {
                                ApplicationRecords.Aggregate result =
                                        save(command, actor, false, true);
                                status.setRollbackOnly();
                                return result;
                            });
                });
    }

    /** 工作草稿复用正式写入的逐记录操作授权，不把 CREATE 的字段范围交叉用于 UPDATE。 */
    public ApplicationAuthorization.Capabilities workWriteCapabilities(
            String app, String object, String id, long actor) {
        return transactions.tx(
                () -> {
                    DataCenter.Definition definition = contexts.definition(app, object, actor);
                    RuntimeSchema.Table table = schemas.main(definition);
                    persistence.writable(table);
                    ApplicationRuntimePolicy.Access access = policy.access(app, definition, actor);
                    if (id == null)
                        return access.require(Long.toString(actor), ApplicationActionEnum.CREATE);
                    ApplicationRecords.Row row =
                            persistence.authorizedRead(
                                    table, id, actor, false, access, ApplicationActionEnum.UPDATE);
                    processes.requireIdle(object, id);
                    return row.permissions();
                });
    }

    /** 草稿只校验已经填写的引用和选项；不求默认值、不校验缺失必填项、不写业务表。 */
    public void validateWorkDraftReferences(
            String app,
            String object,
            String id,
            String formId,
            Map<String, Object> input,
            long actor) {
        transactions.tx(
                () -> {
                    DataCenter.Definition d = contexts.definition(app, object, actor);
                    RuntimeSchema.Table table = schemas.main(d);
                    ApplicationAuthorization.Capabilities caps =
                            workWriteCapabilities(app, object, id, actor);
                    if (input == null || !caps.writeFields().containsAll(input.keySet()))
                        throw invalid("草稿包含无权修改的字段");
                    Map<String, Object> payload = new LinkedHashMap<>();
                    input.forEach(
                            (field, value) -> {
                                String column = table.columns().get(field);
                                if (column == null) throw invalid("草稿字段不属于当前业务表");
                                payload.put(column, value);
                            });
                    schemas.requireWriteCompatible(table, payload.keySet());
                    Map<String, Object> previous =
                            id == null
                                    ? Map.<String, Object>of()
                                    : persistence.read(table, id, null, actor, false).values();
                    relationAccess.validateReferences(app, d, table, payload, actor);
                    directoryValues.validate(table, payload, previous);
                    selectionCatalog.validate(table, payload, previous);
                    selections.validateSelectionScene(
                            app,
                            d,
                            table,
                            selections.selectionForm(app, object, formId),
                            payload,
                            previous,
                            actor,
                            caps.readFields(),
                            null,
                            id);
                    ruleEnforcer.validateReferences(
                            new RuleContext(app, d, actor, null, null),
                            RecordPersistence.mergeValues(table, previous, payload),
                            previous,
                            id == null);
                    return null;
                });
    }

    /** 填写草稿可缺必填值，但不得伪造明细归属、版本或已填写的对象引用。 */
    public void validateWorkDraftDetails(
            String app, String object, String parent, Map<String, List<Row>> groups, long actor) {
        transactions.tx(
                () -> {
                    var d = contexts.definition(app, object, actor);
                    var caps = workWriteCapabilities(app, object, parent, actor);
                    var ruleContext = new RuleContext(app, d, actor, null, null);
                    Map<String, Object> mainValues =
                            parent == null
                                    ? Map.of()
                                    : persistence
                                            .read(schemas.main(d), parent, null, actor, false)
                                            .values();
                    if (groups == null
                            || groups.size() > 20
                            || !caps.writeDetails().containsAll(groups.keySet()))
                        throw invalid("草稿明细无效或无权修改");
                    for (Map.Entry<String, List<ApplicationRecords.Row>> entry :
                            groups.entrySet()) {
                        DataCenter.Detail detail =
                                d.details().stream()
                                        .filter(
                                                t ->
                                                        t.id().equals(entry.getKey())
                                                                && MemberStateEnum.ACTIVE.matches(
                                                                        t.state()))
                                        .findFirst()
                                        .orElseThrow(() -> invalid("草稿明细不存在"));
                        RuntimeSchema.Table table = schemas.detail(d, detail);
                        persistence.writable(table);
                        if (entry.getValue() == null || entry.getValue().size() > 500)
                            throw invalid("草稿明细最多 500 行");
                        Map<String, Row> before = new HashMap<>();
                        if (parent != null)
                            records
                                    .rows(table.statement(null, parent, Long.toString(actor), true))
                                    .stream()
                                    .map(persistence::row)
                                    .forEach(row -> before.put(row.id(), row));
                        if (before.size() > 500) throw invalid("当前明细未完整加载，不能暂存替换集合");
                        Set<String> used = new HashSet<>();
                        for (ApplicationRecords.Row row : entry.getValue()) {
                            if (row == null || row.values() == null) throw invalid("草稿明细行无效");
                            if (row.id() != null) {
                                if (!used.add(row.id()) || !before.containsKey(row.id()))
                                    throw invalid("草稿明细重复或不属于当前主单据");
                                persistence.checkRevision(before.get(row.id()), row.revision());
                            } else if (row.revision() != null) throw invalid("新增草稿明细不能指定修订");
                            Map<String, Object> old =
                                    row.id() == null
                                            ? Map.<String, Object>of()
                                            : before.get(row.id()).values();
                            LinkedHashMap<String, Object> payload =
                                    new LinkedHashMap<String, Object>();
                            for (FieldDefinition field : table.fields())
                                if (row.values().containsKey(field.id()))
                                    payload.put(
                                            table.column(field),
                                            values.convert(
                                                    field,
                                                    table.options()
                                                            .getOrDefault(
                                                                    field.id(),
                                                                    DataCenter.FieldOptions
                                                                            .defaults()),
                                                    row.values().get(field.id())));
                            schemas.requireWriteCompatible(table, payload.keySet());
                            relationAccess.validateReferences(app, d, table, payload, actor);
                            directoryValues.validate(table, payload, old);
                            selectionCatalog.validate(table, payload, old);
                            // 草稿不求只读联动和公式默认值，只复核已填写引用；主表取当前已保存值。
                            ruleEnforcer.validateRowReferences(
                                    ruleContext,
                                    detail,
                                    RecordPersistence.mergeValues(table, old, payload),
                                    old,
                                    mainValues,
                                    mainValues,
                                    row.id() == null);
                        }
                    }
                    return null;
                });
    }

    Aggregate save(Save command, long actor, boolean importing) {
        return save(command, actor, importing, false);
    }

    Aggregate save(Save command, long actor, boolean importing, boolean preparing) {
        return save(command, actor, importing, preparing, null);
    }

    private Aggregate save(
            Save command,
            long actor,
            boolean importing,
            boolean preparing,
            OrderedImportBatch batch) {
        AutomationWriteScope.Context automation = AutomationWriteScope.current();
        if (command != null
                && automation != null
                && automation.taskDelegated()
                && !AutomationWriteScope.covers(command)) throw invalid("自动更新只能写入本次规则声明的目标记录和字段");
        if (batch != null
                && (!importing
                        || preparing
                        || command.id() != null
                        || command.requestKey() != null)) throw invalid("当前保存不能延后有序计算");
        return transactions.tx(
                () -> {
                    if (command == null) throw invalid("缺少业务记录");
                    automations.lock(command.objectId());
                    linkageSync.lock(command.objectId());
                    ordered.lock(command.objectId());
                    DataCenter.Definition d =
                            contexts.definition(command.applicationId(), command.objectId(), actor);
                    ordered.requireCompatible(d);
                    com.lingan.ucp.nocode.runtime.dal.dataobject.DocumentReceiptDO successful =
                            receipts.successful(command, actor);
                    if (successful != null) {
                        ApplicationRecords.SaveReceipt receipt =
                                reader.receiptResult(command.applicationId(), d, successful, actor);
                        if (receipt.result() == null)
                            throw invalid("该请求已保存成功，但当前无权读取原保存结果；请勿更换请求标识重新新建");
                        return receipt.result();
                    }
                    List<RecordAutomations.Before> automationBefore =
                            preparing
                                    ? List.<RecordAutomations.Before>of()
                                    : automations.before(command.objectId(), command.id(), actor);
                    // 数据联动自动更新：按触发应用的发布版本取规则，记下改前锚定到的目标；预备校验模式同样不取。
                    var linkageBefore =
                            preparing
                                    ? List.<RecordLinkageSync.Before>of()
                                    : linkageSync.before(
                                            command.applicationId(),
                                            command.objectId(),
                                            command.id(),
                                            actor);
                    // 系统写联动的自动更新字段：这一条命令豁免流程保护（目标在审批流程中也照常更新）。
                    boolean linkageWrite = LinkageWriteScope.covers(command);
                    String operationId = UUID.randomUUID().toString();
                    ApplicationRuntimePolicy.Access access =
                            policy.access(command.applicationId(), d, actor);
                    RuntimeSchema.Table t = schemas.main(d);
                    persistence.writable(t);
                    boolean insert = command.id() == null;
                    DataCenter.Definition historyDefinition =
                            preparing
                                    ? objects.getPublished(d.objectId())
                                    : history.begin(d, actor);
                    String historyBefore =
                            preparing
                                    ? null
                                    : history.capture(historyDefinition, command.id(), actor);
                    // 必须先持有记录锁，再检查流程状态，避免与并发发起流程交错。
                    if (!insert) {
                        persistence.authorizedRead(
                                t, command.id(), actor, true, access, ApplicationActionEnum.UPDATE);
                        if (!linkageWrite) processes.requireIdle(d.objectId(), command.id());
                    }
                    ApplicationAuthorization.Capabilities caps =
                            insert
                                    ? access.require(
                                            Long.toString(actor), ApplicationActionEnum.CREATE)
                                    : persistence
                                            .authorizedRead(
                                                    t,
                                                    command.id(),
                                                    actor,
                                                    true,
                                                    access,
                                                    ApplicationActionEnum.UPDATE)
                                            .permissions();
                    Map<String, Object> input =
                            contexts.contextualValues(command, t, access, actor);
                    Set<String> writableFields = new HashSet<>(caps.writeFields());
                    ApplicationUi.Form form =
                            selections.selectionForm(
                                    command.applicationId(), command.objectId(), command.formId());
                    if (form != null) {
                        if (form.options() != null
                                && Boolean.TRUE.equals(form.options().readOnly()))
                            throw invalid("当前表单为只读");
                        Map<String, ApplicationUi.FieldPresentation> nodes =
                                SelectionFields.presentations(form.nodes());
                        Set<String> formFields = new HashSet<>(nodes.keySet());
                        // 关联区生成的赋值来自服务端发布关系，只扩展本次命令的表单范围，仍受字段权限和只读约束。
                        formFields.addAll(RelatedWriteScope.fields(command));
                        // 发布页面派生的关联赋值可在表单外，但不能超出对象的字段写权限。
                        input.keySet().stream()
                                .filter(
                                        id ->
                                                command.values() == null
                                                        || !command.values().containsKey(id))
                                .forEach(formFields::add);
                        writableFields.retainAll(formFields);
                        nodes.forEach(
                                (id, p) -> {
                                    if (p != null && Boolean.TRUE.equals(p.readOnly()))
                                        writableFields.remove(id);
                                });
                        if (command.details() != null
                                && !form.detailIds().containsAll(command.details().keySet()))
                            throw invalid("明细不属于当前表单");
                    }
                    if (form != null
                            && form.options() != null
                            && Boolean.TRUE.equals(form.options().relationLayout())
                            && command.relations() != null) {
                        Map<String, ApplicationUi.FieldPresentation> nodes =
                                SelectionFields.presentations(form.nodes());
                        for (String id : command.relations().keySet()) {
                            String key = "relation_" + id;
                            if (!nodes.containsKey(key)
                                    || nodes.get(key) != null
                                            && Boolean.TRUE.equals(nodes.get(key).readOnly()))
                                throw invalid("关系不属于当前表单或为只读");
                        }
                    }
                    // 导入的自动编号和上下文关联赋值，同样不得超过 IMPORT 的字段范围。
                    if (importing)
                        writableFields.retainAll(
                                access.require(Long.toString(actor), ApplicationActionEnum.IMPORT)
                                        .writeFields());
                    if (input == null || !writableFields.containsAll(input.keySet()))
                        throw invalid("包含未定义或无权修改的字段");
                    if (command.details() != null
                            && !caps.writeDetails().containsAll(command.details().keySet()))
                        throw invalid("包含无权修改的明细");
                    if (command.relations() != null
                            && !caps.writeRelations().containsAll(command.relations().keySet()))
                        throw invalid("包含无权修改的多对多关系");
                    if (insert && command.expectedRevision() != null) throw invalid("新记录不能指定版本");
                    if (!insert)
                        persistence.checkRevision(
                                persistence.read(t, command.id(), null, actor, true),
                                command.expectedRevision());
                    Map<String, Object> previousValues =
                            insert
                                    ? Map.<String, Object>of()
                                    : persistence
                                            .read(t, command.id(), null, actor, false)
                                            .values();
                    DocumentPolicies.Input documentBefore =
                            DocumentPolicies.policy(historyDefinition) == null
                                    ? null
                                    : documents.documentInput(
                                            historyDefinition, command.id(), actor, Map.of());
                    Map<String, Object> behavioralInput =
                            com.lingan.ucp.nocode.metadata.service.form.FormBehaviors.apply(
                                    form,
                                    com.lingan.ucp.nocode.metadata.service.form.FormFillBindings
                                            .clearEmptySources(
                                                    form,
                                                    input,
                                                    previousValues,
                                                    writableFields,
                                                    d.fieldOptions()),
                                    previousValues,
                                    caps.readFields(),
                                    d.fields());
                    Map<String, Object> stateInput =
                            DocumentStates.prepare(
                                    historyDefinition,
                                    documentBefore == null
                                            ? previousValues
                                            : documentBefore.values(),
                                    behavioralInput,
                                    command.actionCode(),
                                    caps.actions(),
                                    insert);
                    // 只读限制用户输入；发布表单中的固定默认值由服务端在新建时按原字段授权求值。
                    // 客户端字段已在上方校验，不能借受控默认值路径修改只读字段。
                    Set<String> initializedFields = new HashSet<>(writableFields);
                    DocumentPolicy documentPolicy = DocumentPolicies.policy(historyDefinition);
                    if (documentPolicy != null && documentPolicy.lifecycle() != null)
                        initializedFields.add(documentPolicy.lifecycle().fieldId());
                    if (insert && form != null && !importing)
                        SelectionFields.presentations(form.nodes())
                                .forEach(
                                        (id, presentation) -> {
                                            if (presentation != null
                                                    && Boolean.TRUE.equals(presentation.readOnly())
                                                    && presentation.selection() != null
                                                    && presentation.selection().defaultValue()
                                                            != null
                                                    && caps.writeFields().contains(id))
                                                initializedFields.add(id);
                                        });
                    Map<String, Object> prepared =
                            selections.selectionDefaults(
                                    t,
                                    stateInput,
                                    insert,
                                    actor,
                                    initializedFields,
                                    form == null
                                            ? Map.of()
                                            : SelectionFields.presentations(form.nodes()));
                    Map<String, Object> businessInput =
                            businessRules.prepare(
                                    command.applicationId(),
                                    d,
                                    prepared,
                                    insert,
                                    actor,
                                    initializedFields);
                    businessInput =
                            automations.prepare(
                                    command.objectId(),
                                    command.id(),
                                    businessInput,
                                    command.values(),
                                    actor);
                    businessInput =
                            captures.prepare(
                                    d, command.id(), businessInput, command.values(), actor);
                    // 对象规则：只读联动按服务端合并值重算强制，公式默认值只在新建时补空值（设计稿 6.1）。
                    var ruleContext =
                            new RuleContext(command.applicationId(), d, actor, null, command.id());
                    Set<String> ruleWritable = new HashSet<>(caps.writeFields());
                    if (importing)
                        ruleWritable.retainAll(
                                access.require(Long.toString(actor), ApplicationActionEnum.IMPORT)
                                        .writeFields());
                    // 开启「来源变化时自动更新」的联动字段是系统写入，不受操作者字段写权限约束：否则没有该字段写权限的人
                    // 新建记录时它落空而不是「没有匹配记录时填入」的值。客户端自己提交这个字段仍在上方按写权限拒绝。
                    ruleWritable.addAll(autoUpdateFields(d));
                    businessInput =
                            ruleEnforcer.prepare(
                                    ruleContext,
                                    insert,
                                    previousValues,
                                    businessInput,
                                    input.keySet(),
                                    ruleWritable);
                    var formCandidate = new HashMap<>(previousValues);
                    formCandidate.putAll(businessInput);
                    com.lingan.ucp.nocode.metadata.service.form.FormBehaviors.require(
                            form, formCandidate, d.fields());
                    Map<String, Object> payload =
                            values.normalize(t, businessInput, insert, previousValues);
                    relationAccess.validateReferences(
                            command.applicationId(), d, t, payload, actor);
                    directoryValues.validate(t, payload, previousValues);
                    selectionCatalog.validate(t, payload, previousValues);
                    selections.validateSelectionScene(
                            command.applicationId(),
                            d,
                            t,
                            form,
                            payload,
                            previousValues,
                            actor,
                            caps.readFields(),
                            command.relations(),
                            command.id());
                    // 引用筛选复核只信服务端合并值，客户端不能用伪造的表单值绕过（6.2）。
                    ruleEnforcer.validateReferences(
                            ruleContext,
                            RecordPersistence.mergeValues(t, previousValues, payload),
                            previousValues,
                            insert);
                    var preparedDetails =
                            details.prepareDetails(
                                    withRuleDetails(
                                            command,
                                            d,
                                            caps,
                                            insert,
                                            previousValues,
                                            formCandidate,
                                            actor),
                                    d,
                                    actor,
                                    previousValues,
                                    formCandidate,
                                    caps.readFields());
                    Map<String, Object> handlingValues =
                            RecordPersistence.mergeValues(t, previousValues, payload);
                    if (documentBefore != null) {
                        Map<String, Object> main =
                                RecordPersistence.mergeValues(
                                        schemas.main(historyDefinition),
                                        documentBefore.values(),
                                        payload);
                        LinkedHashMap<String, List<DocumentPolicies.InputRow>> groups =
                                new LinkedHashMap<>(documentBefore.details());
                        preparedDetails.forEach(
                                group ->
                                        groups.put(
                                                group.detail().id(),
                                                group.lines().stream()
                                                        .map(
                                                                line ->
                                                                        new DocumentPolicies
                                                                                .InputRow(
                                                                                line.row().id(),
                                                                                line.key(),
                                                                                line.values()))
                                                        .toList()));
                        DocumentPolicies.Input candidate =
                                DocumentCalculations.calculate(
                                        historyDefinition,
                                        RecordDocumentValidation.typedDocument(
                                                historyDefinition,
                                                new DocumentPolicies.Input(main, groups)),
                                        fields ->
                                                calculations.preview(
                                                        command.applicationId(),
                                                        historyDefinition,
                                                        command.id(),
                                                        fields,
                                                        command.relations(),
                                                        actor));
                        RecordDocumentValidation.requireDocument(
                                historyDefinition, documentBefore, candidate, caps);
                        // 审批材料只约束被审批的那条来源命令；系统回写目标记录不拿目标整单去和来源的材料比对。
                        if (!linkageWrite)
                            com.lingan.ucp.nocode.runtime.service.handling.HandlingWriteScope
                                    .verify(candidate);
                        handlingValues = candidate.values();
                    }
                    relationAccess.saveRelations(command, d, command.id(), insert, actor, false);
                    if (preparing) {
                        Map<String, List<Row>> groups = new LinkedHashMap<>();
                        if (!insert)
                            groups.putAll(
                                    reader.get(
                                                    command.applicationId(),
                                                    d.objectId(),
                                                    command.id(),
                                                    actor)
                                            .details());
                        preparedDetails.forEach(
                                group ->
                                        groups.put(
                                                group.detail().id(),
                                                group.lines().stream()
                                                        .map(
                                                                line ->
                                                                        new Row(
                                                                                line.row().id(),
                                                                                line.row()
                                                                                        .revision(),
                                                                                line.values(),
                                                                                null,
                                                                                Map.of(),
                                                                                line.key()))
                                                        .toList()));
                        Map<String, List<String>> links =
                                new LinkedHashMap<>(
                                        insert
                                                ? Map.of()
                                                : relationAccess.readRelations(
                                                        d,
                                                        command.id(),
                                                        actor,
                                                        caps.readRelations()));
                        if (command.relations() != null)
                            command.relations()
                                    .forEach(
                                            (relationId, ids) -> {
                                                if (caps.readRelations().contains(relationId))
                                                    links.put(relationId, ids);
                                            });
                        ApplicationRecords.Row preview =
                                new Row(
                                        command.id(),
                                        command.expectedRevision(),
                                        handlingValues,
                                        caps);
                        return new Aggregate(
                                selections
                                        .selectionLabels(
                                                command.applicationId(),
                                                d,
                                                List.of(preview),
                                                actor,
                                                null,
                                                links)
                                        .getFirst(),
                                selections.selectionDetailLabels(
                                        command.applicationId(), d, groups, actor),
                                List.of(),
                                links);
                    }
                    if (!linkageWrite
                            && com.lingan.ucp.nocode.metadata.service.form.BusinessHandlingPolicies
                                    .required(historyDefinition, insert, handlingValues)
                            && !com.lingan.ucp.nocode.runtime.service.handling.HandlingWriteScope
                                    .permits(command, actor))
                        throw invalid("此操作需要审批，请使用提交申请；审批通过前业务数据保持原状");
                    if (insert) autoNumbers.generate(d.objectId(), t, payload, actor);
                    taskScope.requireWrite();
                    com.lingan.ucp.nocode.runtime.dal.query.RecordStatement sql =
                            persistence.writeStatement(t, command.id(), null, payload, actor);
                    String id;
                    try {
                        id = insert ? records.insert(sql) : command.id();
                        if (insert) taskScope.created(d.objectId(), id);
                        if (!insert && records.update(sql) != 1) throw persistence.conflict();
                    } catch (DataIntegrityViolationException error) {
                        throw RecordConstraintErrors.translate(error, t);
                    }
                    changes.changed(
                            d.objectId(),
                            id,
                            insert
                                    ? RecordChangeOperationEnum.CREATE
                                    : RecordChangeOperationEnum.UPDATE);
                    Map<String, Map<String, String>> rowKeys = new LinkedHashMap<>();
                    for (RecordDetailWriter.PreparedDetail group : preparedDetails)
                        rowKeys.put(group.detail().id(), details.writeDetails(group, id, actor));
                    relationAccess.saveRelations(command, d, id, insert, actor);
                    calculations.save(command.applicationId(), d, id, actor);
                    if (batch == null)
                        ordered.complete(
                                command.applicationId(), d, id, previousValues, false, actor);
                    // 数据库默认值、生成列及受管计算最终结果也必须满足规则；失败仍回滚当前事务。
                    if (documentBefore != null) {
                        DocumentPolicies.Input stored =
                                documents.documentInput(historyDefinition, id, actor, rowKeys);
                        DocumentPolicies.Input finalDocument =
                                DocumentCalculations.calculate(
                                        historyDefinition,
                                        stored,
                                        fields ->
                                                calculations.preview(
                                                        command.applicationId(),
                                                        historyDefinition,
                                                        id,
                                                        fields,
                                                        null,
                                                        actor));
                        RecordDocumentValidation.requireDocument(
                                historyDefinition, documentBefore, finalDocument, caps);
                        if (!linkageWrite)
                            com.lingan.ucp.nocode.runtime.service.handling.HandlingWriteScope
                                    .verify(finalDocument);
                    }
                    // 业务文件绑定：与记录、明细同一事务；失败整体回滚，不产生记录已提交而网盘不可见的补偿窗口
                    bizFiles.bindOnSave(
                            command.applicationId(),
                            d.objectId(),
                            id,
                            previousValues,
                            RecordPersistence.mergeValues(t, handlingValues, payload),
                            detailRowsForBinding(preparedDetails, rowKeys),
                            actor);
                    java.util.function.Supplier<Aggregate> completion =
                            () -> {
                                // 修改前后均须满足同一操作的范围；失败由当前事务整体回滚。
                                ApplicationAuthorization.Capabilities after =
                                        persistence
                                                .authorizedRead(
                                                        t,
                                                        id,
                                                        actor,
                                                        false,
                                                        access,
                                                        insert
                                                                ? ApplicationActionEnum.CREATE
                                                                : ApplicationActionEnum.UPDATE)
                                                .permissions();
                                if (!after.writeFields().containsAll(input.keySet())
                                        || command.details() != null
                                                && !after.writeDetails()
                                                        .containsAll(command.details().keySet())
                                        || command.relations() != null
                                                && !after.writeRelations()
                                                        .containsAll(command.relations().keySet()))
                                    throw invalid("保存后的记录超出了字段或关联授权范围");
                                if (importing
                                        && !persistence
                                                .authorizedRead(
                                                        t,
                                                        id,
                                                        actor,
                                                        false,
                                                        access,
                                                        ApplicationActionEnum.IMPORT)
                                                .permissions()
                                                .writeFields()
                                                .containsAll(input.keySet()))
                                    throw invalid("导入记录超出授权范围");
                                boolean orderedFinal = !ordered.fields(d).isEmpty();
                                // 普通记录仍保留既有的人工变更历史时点；有序记录封存最终联动结果。
                                if (!orderedFinal)
                                    history.finish(
                                            historyDefinition,
                                            command.applicationId(),
                                            id,
                                            historyBefore,
                                            actor,
                                            operationId,
                                            receipts.policyVersion(historyDefinition));
                                automations.after(
                                        automationBefore,
                                        id,
                                        insert
                                                ? RecordChangeOperationEnum.CREATE.getCode()
                                                : RecordChangeOperationEnum.UPDATE.getCode(),
                                        actor);
                                // 数据联动自动更新：排在自动更新规则回写之后，读到的是来源的最终值。
                                linkageSync.after(
                                        linkageBefore,
                                        command.objectId(),
                                        id,
                                        insert
                                                ? RecordChangeOperationEnum.CREATE.getCode()
                                                : RecordChangeOperationEnum.UPDATE.getCode(),
                                        actor);
                                if (orderedFinal && documentBefore != null) {
                                    com.lingan.ucp.nocode.metadata.service.form.DocumentPolicies
                                                    .Input
                                            finalInput =
                                                    DocumentCalculations.calculate(
                                                            historyDefinition,
                                                            documents.documentInput(
                                                                    historyDefinition,
                                                                    id,
                                                                    actor,
                                                                    rowKeys),
                                                            fields ->
                                                                    calculations.preview(
                                                                            command.applicationId(),
                                                                            historyDefinition,
                                                                            id,
                                                                            fields,
                                                                            null,
                                                                            actor));
                                    RecordDocumentValidation.requireDocument(
                                            historyDefinition, documentBefore, finalInput, caps);
                                    if (!linkageWrite)
                                        com.lingan.ucp.nocode.runtime.service.handling
                                                .HandlingWriteScope.verify(finalInput);
                                }
                                if (orderedFinal)
                                    history.finish(
                                            historyDefinition,
                                            command.applicationId(),
                                            id,
                                            historyBefore,
                                            actor,
                                            operationId,
                                            receipts.policyVersion(historyDefinition));
                                ApplicationRecords.Aggregate result =
                                        withRowKeys(
                                                reader.get(
                                                        command.applicationId(),
                                                        command.objectId(),
                                                        id,
                                                        actor),
                                                rowKeys);
                                receipts.append(
                                        command, result, historyDefinition, operationId, actor);
                                return result;
                            };
                    if (batch != null) {
                        // 原导入不返回逐行 Aggregate，也没有幂等请求键；最终权限与历史仍在批末逐行完成。
                        batch.add(id, completion);
                        return null;
                    }
                    return completion.get();
                });
    }

    /**
     * API 省略明细组而主表依赖变化时，服务端补齐整组（全部现有行、值为空），使只读联动重算、引用筛选复核（15.4.5）。
     *
     * <p>只补操作者有明细写权限的分组；补齐的行保持原顺序，不删除任何行。无写权限时不补齐，明细保持原值。
     */
    private Save withRuleDetails(
            Save command,
            DataCenter.Definition d,
            ApplicationAuthorization.Capabilities caps,
            boolean insert,
            Map<String, Object> previous,
            Map<String, Object> candidate,
            long actor) {
        if (insert) return command;
        Set<String> submitted = command.details() == null ? Set.of() : command.details().keySet();
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (String detailId : ruleEnforcer.detailsAffectedByMaster(d, previous, candidate)) {
            if (submitted.contains(detailId) || !caps.writeDetails().contains(detailId)) continue;
            var detail =
                    d.details().stream()
                            .filter(
                                    x ->
                                            x.id().equals(detailId)
                                                    && MemberStateEnum.ACTIVE.matches(x.state()))
                            .findFirst()
                            .orElse(null);
            if (detail == null) continue;
            var table = schemas.detail(d, detail);
            var rows =
                    records
                            .rows(table.statement(null, command.id(), Long.toString(actor), true))
                            .stream()
                            .map(persistence::row)
                            .toList();
            groups.put(
                    detailId,
                    detailPositions
                            .order(d.objectId(), detailId, command.id(), rows, Row::id)
                            .stream()
                            .map(row -> new Row(row.id(), row.revision(), Map.of()))
                            .toList());
        }
        if (groups.isEmpty()) return command;
        Map<String, List<Row>> detailGroups = new LinkedHashMap<>();
        if (command.details() != null) detailGroups.putAll(command.details());
        detailGroups.putAll(groups);
        return new Save(
                command.applicationId(),
                command.objectId(),
                command.id(),
                command.expectedRevision(),
                command.values(),
                detailGroups,
                command.relations(),
                command.context(),
                command.formId(),
                command.requestKey(),
                command.actionCode(),
                command.relatedRecords());
    }

    /** 对象主表上开启了「来源变化时自动更新」的联动字段。 */
    static Set<String> autoUpdateFields(DataCenter.Definition d) {
        Set<String> result = new HashSet<>();
        for (FieldDefinition field : d.fields()) {
            DataCenter.FieldOptions options = d.fieldOptions().get(field.id());
            if (options == null
                    || MemberStateEnum.INACTIVE.matches(options.state())
                    || options.rules() == null
                    || options.rules().linkage() == null) continue;
            if (options.rules().linkage().autoUpdateOn()) result.add(field.id());
        }
        return result;
    }

    static Aggregate withRowKeys(Aggregate value, Map<String, Map<String, String>> keys) {
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        value.details()
                .forEach(
                        (detail, rows) ->
                                groups.put(
                                        detail,
                                        rows.stream()
                                                .map(
                                                        r ->
                                                                new Row(
                                                                        r.id(),
                                                                        r.revision(),
                                                                        r.values(),
                                                                        r.permissions(),
                                                                        r.displayValues(),
                                                                        keys.getOrDefault(
                                                                                        detail,
                                                                                        Map.of())
                                                                                .get(r.id())))
                                                .toList()));
        return new Aggregate(value.record(), groups, value.processes(), value.relations());
    }

    /** 动作取自当前不可变发布版本，客户端只能指定动作与记录，不能替换动作内容。 */
    /** 执行发布配置中的业务动作，动作产生的记录变化仍经过公共保存校验。 */
    /** 汇总明细行差集供业务文件绑定：写入后的行保持写入顺序（与行键映射同序），被删除的行单列 */
    private List<BizFileBindingService.DetailRows> detailRowsForBinding(
            List<RecordDetailWriter.PreparedDetail> groups,
            Map<String, Map<String, String>> rowKeys) {
        List<BizFileBindingService.DetailRows> result = new ArrayList<>();
        for (RecordDetailWriter.PreparedDetail group : groups) {
            Map<String, String> keys = rowKeys.getOrDefault(group.detail().id(), Map.of());
            List<String> writtenIds = new ArrayList<>(keys.keySet());
            List<BizFileBindingService.RowValues> retained = new ArrayList<>();
            List<RecordDetailWriter.PreparedLine> lines = group.lines();
            for (int index = 0; index < lines.size() && index < writtenIds.size(); index++) {
                RecordDetailWriter.PreparedLine line = lines.get(index);
                Map<String, Object> previous =
                        line.row().id() == null ? Map.of() : line.row().values();
                retained.add(
                        new BizFileBindingService.RowValues(
                                writtenIds.get(index), previous, line.values()));
            }
            List<String> removed = new ArrayList<>();
            for (var row : group.previous()) if (!keys.containsKey(row.id())) removed.add(row.id());
            result.add(
                    new BizFileBindingService.DetailRows(group.detail().id(), retained, removed));
        }
        return result;
    }

    public Aggregate execute(ApplicationBusiness.Execute command, long actor) {
        return transactions.tx(
                () -> {
                    if (command == null) throw invalid("缺少业务动作");
                    automations.lock(command.objectId());
                    linkageSync.lock(command.objectId());
                    ordered.lock(command.objectId());
                    contexts.definition(command.applicationId(), command.objectId(), actor);
                    ApplicationCenter.Resource resource =
                            applications
                                    .published(command.applicationId())
                                    .definition()
                                    .resources()
                                    .stream()
                                    .filter(
                                            r ->
                                                    r.id().equals(command.actionId())
                                                            && ApplicationResourceKindEnum.ACTION
                                                                    .matches(r.kind()))
                                    .findFirst()
                                    .orElseThrow(() -> invalid("已发布业务动作不存在"));
                    ApplicationBusiness.Action action =
                            resourceValidator.decode(
                                    resource.config(), ApplicationBusiness.Action.class);
                    if (!Objects.equals(action.objectId(), command.objectId())
                            || command.recordId() == null) throw invalid("动作与业务记录不匹配");
                    if (BusinessActionKindEnum.CAPTURE_VALUES.matches(action.kind())) {
                        ApplicationCenter.Published published =
                                applications.published(command.applicationId());
                        DataCenter.Definition definition =
                                contexts.definition(
                                        command.applicationId(), command.objectId(), actor);
                        int objectVersion =
                                published.definition().objects().stream()
                                        .filter(ref -> ref.objectId().equals(command.objectId()))
                                        .findFirst()
                                        .orElseThrow()
                                        .versionNo();
                        return captures.execute(
                                command,
                                action,
                                definition,
                                published.versionNo(),
                                objectVersion,
                                resource.name(),
                                actor);
                    }
                    if (BusinessActionKindEnum.START_PROCESS.matches(action.kind())) {
                        DataCenter.Definition d =
                                contexts.definition(
                                        command.applicationId(), command.objectId(), actor);
                        RuntimeSchema.Table t = schemas.main(d);
                        persistence.writable(t);
                        ApplicationRecords.Row record =
                                persistence.authorizedRead(
                                        t,
                                        command.recordId(),
                                        actor,
                                        true,
                                        policy.access(command.applicationId(), d, actor),
                                        ApplicationActionEnum.START_PROCESS);
                        persistence.checkRevision(record, command.expectedRevision());
                        processes.start(
                                applications.published(command.applicationId()),
                                d,
                                resource,
                                action,
                                record,
                                actor);
                        // 即使流程同步结束也推进记录版本，阻止同一按钮提交被重复消费。
                        if (records.update(
                                        persistence.writeStatement(
                                                t, record.id(), null, Map.of(), actor))
                                != 1) throw persistence.conflict();
                        changes.changed(
                                command.objectId(), record.id(), RecordChangeOperationEnum.UPDATE);
                        return reader.get(
                                command.applicationId(),
                                command.objectId(),
                                command.recordId(),
                                actor);
                    }
                    return save(
                            new Save(
                                    command.applicationId(),
                                    command.objectId(),
                                    command.recordId(),
                                    command.expectedRevision(),
                                    action.values(),
                                    null),
                            actor);
                });
    }
}
