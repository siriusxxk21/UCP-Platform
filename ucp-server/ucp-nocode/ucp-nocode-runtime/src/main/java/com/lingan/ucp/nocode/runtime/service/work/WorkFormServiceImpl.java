package com.lingan.ucp.nocode.runtime.service.work;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.mybatis.core.metadata.BaseDOColumns;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.work.*;
import com.lingan.ucp.nocode.application.service.published.ApplicationPublishedService;
import com.lingan.ucp.nocode.application.service.resource.ApplicationResourceValidator;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.runtime.dal.dataobject.BizFileRetentionDO;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizFileRetentionService;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizUploadSessionService;
import com.lingan.ucp.nocode.runtime.service.record.RecordQueryAccess;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.record.RecordValues;
import com.lingan.ucp.nocode.runtime.service.record.RelatedFormService;
import com.lingan.ucp.nocode.work.service.draft.WorkDraftService;
import com.lingan.ucp.nocode.work.service.submission.WorkSubmissionService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 已发布表单的草稿与正式提交。所有读取重新验证当前记录／字段权限。
 *
 * <p>正式写入、材料封存和草稿关闭共用外层事务；未填必填项的草稿不触及业务表。
 */
@Service
public class WorkFormServiceImpl implements WorkFormService {
    @Resource private ApplicationPublishedService publishedService;
    @Resource private ApplicationResourceValidator resourceValidator;

    // 底座日志组件也注册了 recordService，必须使用业务服务的显式 Bean 名。
    @Resource(name = "nocodeRecordService")
    private RecordService recordService;

    @Resource private RecordValues recordValues;
    @Resource private RecordQueryAccess recordQueries;
    @Resource private RelatedFormService relatedForms;
    @Resource private WorkDraftService draftService;
    @Resource private WorkSubmissionService submissionService;
    @Resource private BizFileRetentionService fileRetentions;
    @Resource private BizUploadSessionService uploadSessions;

    @Resource
    private com.lingan.ucp.nocode.runtime.service.record.DocumentReceipts documentReceipts;

    @Resource private DataObjectApi objects;
    @Resource private PlatformTransactionManager transactionManager;
    private TransactionTemplate transaction;

    @PostConstruct
    void initialize() {
        transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public WorkDrafts.Draft saveDraft(WorkDrafts.Save command, long actor, WorkSourceRef source) {
        if (source == null || command == null || command.resource() == null)
            throw invalid("缺少工作草稿");
        return transaction.execute(
                status -> {
                    if (command.id() == null && source.type() == WorkSourceEnum.BUSINESS_FORM) {
                        var current =
                                publishedService.getCurrent(command.resource().applicationId());
                        if (current.versionNo() != command.resource().applicationVersion()
                                || !Objects.equals(
                                        current.checksum(),
                                        command.resource().applicationChecksum()))
                            throw new ServiceException(CONFLICT, "应用已发布新版本，请刷新后创建工作草稿");
                    }
                    return publishedService.withVersion(
                            command.resource(),
                            () -> {
                                var access =
                                        access(
                                                command.resource(),
                                                command.objectId(),
                                                command.recordId(),
                                                actor,
                                                true);
                                if (command.recordId() == null
                                                && command.baseRecordRevision() != null
                                        || command.recordId() != null
                                                && !Objects.equals(
                                                        access.recordRevision(),
                                                        command.baseRecordRevision()))
                                    throw new ServiceException(CONFLICT, "业务记录已变化，请比较并刷新草稿基础版本");
                                // 任务入口正式保存直接复用业务文件引用；其他来源的材料封存边界保持不变。
                                boolean entryDraft = source.type() == WorkSourceEnum.TASK_ENTRY;
                                var input = validateValues(command.values(), access, entryDraft);
                                var details =
                                        validateDetails(command.details(), access, entryDraft);
                                var related =
                                        validateRelated(
                                                command.relatedRecords(),
                                                command.resource(),
                                                command.objectId(),
                                                command.recordId(),
                                                access.form(),
                                                actor,
                                                source);
                                recordService.validateWorkDraftDetails(
                                        command.resource().applicationId(),
                                        command.objectId(),
                                        command.recordId(),
                                        details,
                                        actor);
                                recordService.validateWorkDraftReferences(
                                        command.resource().applicationId(),
                                        command.objectId(),
                                        command.recordId(),
                                        command.resource().resourceId(),
                                        input,
                                        actor);
                                WorkDrafts.Draft saved =
                                        draftService.save(
                                                new WorkDrafts.Save(
                                                        command.id(),
                                                        command.expectedRevision(),
                                                        command.resource(),
                                                        command.objectId(),
                                                        command.recordId(),
                                                        command.baseRecordRevision(),
                                                        input,
                                                        details,
                                                        related),
                                                actor,
                                                source);
                                if (entryDraft) {
                                    // 任务入口草稿引用受保护文件：按固定发布版本读取对象规则，登记保留引用并按最终输入整体替换，再顺延本人仍有效的上传会话
                                    DataCenter.Definition definition =
                                            recordQueries.definition(
                                                    command.resource().applicationId(),
                                                    command.objectId(),
                                                    actor);
                                    List<Long> files = businessFileIds(definition, input, details);
                                    fileRetentions.replace(
                                            BizFileRetentionDO.HOLDER_WORK_DRAFT,
                                            saved.id(),
                                            command.objectId(),
                                            command.recordId(),
                                            files,
                                            actor);
                                    uploadSessions.renewFiles(command.objectId(), files, actor);
                                }
                                return saved;
                            });
                });
    }

    @Override
    public WorkDrafts.Draft getDraft(String id, long actor, WorkSourceRef source) {
        return transaction.execute(
                status -> {
                    var draft = draftService.get(id, actor, source);
                    // 已提交输入只能只读展示，沿用材料入口校验实际业务记录及当前授权。
                    // 不能把“草稿裁剪后可能丢失输入”的恢复限制用于已封存材料。
                    if (WorkDraftStateEnum.SUBMITTED.matches(draft.state())) {
                        var stored = submissionService.forDraft(id, actor);
                        if (stored == null) throw invalid("已提交草稿缺少材料，请联系管理员核查");
                        var material = getSubmission(stored.id(), actor, source);
                        var details = new LinkedHashMap<>(draft.details());
                        details.keySet().retainAll(material.details().keySet());
                        return new WorkDrafts.Draft(
                                draft.id(),
                                draft.revision(),
                                draft.state(),
                                draft.resource(),
                                draft.objectId(),
                                draft.recordId(),
                                draft.baseRecordRevision(),
                                visible(draft.values(), material.values().keySet()),
                                draft.updatedAt(),
                                details);
                    }
                    return publishedService.withVersion(
                            draft.resource(),
                            () -> {
                                var access =
                                        access(
                                                draft.resource(),
                                                draft.objectId(),
                                                draft.recordId(),
                                                actor,
                                                false);
                                if (!access.permissions()
                                                .readFields()
                                                .containsAll(draft.values().keySet())
                                        || !access.permissions()
                                                .readDetails()
                                                .containsAll(draft.details().keySet()))
                                    throw invalid("草稿涉及的字段或明细读取权限已变化，不能裁剪后继续提交");
                                return new WorkDrafts.Draft(
                                        draft.id(),
                                        draft.revision(),
                                        draft.state(),
                                        draft.resource(),
                                        draft.objectId(),
                                        draft.recordId(),
                                        draft.baseRecordRevision(),
                                        visible(draft.values(), access.permissions().readFields()),
                                        draft.updatedAt(),
                                        draft.details(),
                                        validateRelated(
                                                draft.relatedRecords(),
                                                draft.resource(),
                                                draft.objectId(),
                                                draft.recordId(),
                                                access.form(),
                                                actor,
                                                source));
                            });
                });
    }

    @Override
    public WorkDrafts.Submission submit(
            WorkDrafts.Submit command, long actor, WorkSourceRef source) {
        try {
            return transaction.execute(
                    status -> {
                        var previous = submissionService.findSuccessful(command, actor);
                        if (previous != null) return getSubmission(previous.id(), actor, source);
                        var draft = draftService.get(command.draftId(), actor, source);
                        if (!draft.relatedRecords().isEmpty())
                            throw invalid("关联草稿请从原任务入口恢复后提交，不能通过普通材料提交入口丢弃关联输入");
                        if (draft.revision() != command.expectedRevision()
                                || !WorkDraftStateEnum.DRAFT.matches(draft.state()))
                            throw new ServiceException(CONFLICT, "工作草稿已修改或提交，请刷新后重试");
                        return publishedService.withVersion(
                                draft.resource(),
                                () -> {
                                    var access =
                                            access(
                                                    draft.resource(),
                                                    draft.objectId(),
                                                    draft.recordId(),
                                                    actor,
                                                    true);
                                    validateValues(draft.values(), access);
                                    validateDetails(draft.details(), access);
                                    // 文件保留／不可变引用和主从草稿协议未接入前明确阻断，不能给出虚假完整封存。
                                    for (var field : access.model().object().fields()) {
                                        if ((FieldTypeEnum.ATTACHMENT.matches(field.type())
                                                        || FieldTypeEnum.IMAGE.matches(
                                                                field.type()))
                                                && draft.values().get(field.id())
                                                        instanceof Collection<?> files
                                                && !files.isEmpty())
                                            throw invalid("当前草稿提交尚未接入附件封存，请使用已有业务保存入口");
                                    }
                                    var result =
                                            recordService.save(
                                                    new ApplicationRecords.Save(
                                                            draft.resource().applicationId(),
                                                            draft.objectId(),
                                                            draft.recordId(),
                                                            draft.baseRecordRevision(),
                                                            draft.values(),
                                                            draft.details(),
                                                            null,
                                                            null,
                                                            draft.resource().resourceId(),
                                                            null,
                                                            command.actionCode()),
                                                    actor);
                                    for (var field : access.model().object().fields()) {
                                        if ((FieldTypeEnum.ATTACHMENT.matches(field.type())
                                                        || FieldTypeEnum.IMAGE.matches(
                                                                field.type()))
                                                && result.record().values().get(field.id())
                                                        instanceof Collection<?> files
                                                && !files.isEmpty())
                                            throw invalid("当前草稿提交尚未接入附件封存，请使用已有业务保存入口");
                                    }
                                    var snapshotValues = new LinkedHashMap<String, Object>();
                                    var fields =
                                            SelectionFields.presentations(access.form().nodes())
                                                    .keySet();
                                    result.record()
                                            .values()
                                            .forEach(
                                                    (key, value) -> {
                                                        if (fields.contains(key))
                                                            snapshotValues.put(key, value);
                                                    });
                                    var material =
                                            new WorkDrafts.Submission(
                                                    UUID.randomUUID().toString(),
                                                    draft.id(),
                                                    draft.resource(),
                                                    draft.objectId(),
                                                    result.record().id(),
                                                    result.record().revision(),
                                                    snapshotValues,
                                                    null,
                                                    materialDetails(
                                                            result.details(),
                                                            draft.details().keySet()),
                                                    visibleLabels(
                                                            result.record().displayValues(),
                                                            snapshotValues.keySet()),
                                                    documentReceipts.policyVersion(
                                                            objects.getPublished(
                                                                    draft.objectId())));
                                    var submitted =
                                            submissionService.create(command, material, actor);
                                    draftService.markSubmitted(
                                            draft.id(), command.expectedRevision(), actor, source);
                                    return submitted;
                                });
                    });
        } catch (DataIntegrityViolationException ex) {
            throw new ServiceException(CONFLICT, "工作提交发生冲突，本次业务写入已回滚");
        }
    }

    @Override
    public WorkDrafts.Submission getSubmission(String id, long actor, WorkSourceRef source) {
        return transaction.execute(
                status -> {
                    var material = submissionService.get(id, actor);
                    draftService.get(material.draftId(), actor, source);
                    return publishedService.withVersion(
                            material.resource(),
                            () -> {
                                var access =
                                        access(
                                                material.resource(),
                                                material.objectId(),
                                                material.recordId(),
                                                actor,
                                                false);
                                return new WorkDrafts.Submission(
                                        material.id(),
                                        material.draftId(),
                                        material.resource(),
                                        material.objectId(),
                                        material.recordId(),
                                        material.recordRevision(),
                                        visible(
                                                material.values(),
                                                access.permissions().readFields()),
                                        material.submittedAt(),
                                        materialDetails(
                                                material.details(),
                                                access.permissions().readDetails()),
                                        visibleLabels(
                                                material.displayValues(),
                                                access.permissions().readFields()),
                                        material.policyVersion());
                            });
                });
    }

    @Override
    public void validateSourceSubmission(String id, long actor, WorkSourceRef source) {
        if (source == null || source.type() != WorkSourceEnum.FLOW_TASK) throw invalid("不支持的提交来源");
        transaction.executeWithoutResult(
                status -> {
                    var material = getSubmission(id, actor, source);
                    var draft = draftService.get(material.draftId(), actor, source);
                    if (!WorkDraftStateEnum.SUBMITTED.matches(draft.state()))
                        throw invalid("任务材料尚未封存");
                    publishedService.withVersion(
                            draft.resource(),
                            () -> {
                                var current =
                                        access(
                                                draft.resource(),
                                                draft.objectId(),
                                                draft.recordId(),
                                                actor,
                                                true);
                                validateValues(draft.values(), current);
                                validateDetails(draft.details(), current);
                                return null;
                            });
                });
    }

    @Override
    public void validateCreateResource(PublishedResourceRef resource, String objectId, long actor) {
        transaction.executeWithoutResult(
                status ->
                        publishedService.withVersion(
                                resource,
                                () -> {
                                    access(resource, objectId, null, actor, true);
                                    return null;
                                }));
    }

    /** 暂存只校验已输入内容，不执行必填校验或写业务记录；恢复不得静默裁剪关联输入。 */
    private Map<String, List<RelatedForms.Row>> validateRelated(
            Map<String, List<RelatedForms.Row>> input,
            PublishedResourceRef reference,
            String objectId,
            String recordId,
            ApplicationUi.Form form,
            long actor,
            WorkSourceRef source) {
        if (input == null || input.isEmpty()) return Map.of();
        if (source.type() != WorkSourceEnum.TASK_ENTRY) throw invalid("此来源暂未接入关联草稿，请从任务入口暂存完整表单");
        if (input.size() > 10) throw invalid("关联草稿区域过多");
        var output = new LinkedHashMap<String, List<RelatedForms.Row>>();
        int count = 0;
        for (var entry : input.entrySet()) {
            var binding =
                    Optional.ofNullable(form.relatedForms()).orElse(List.of()).stream()
                            .filter(b -> Objects.equals(b.id(), entry.getKey()))
                            .findFirst()
                            .orElseThrow(() -> invalid("关联草稿的区域已变化，原草稿保留，请检查发布表单"));
            var resolved =
                    relatedForms.query(
                            new RelatedForms.Query(
                                    reference.applicationId(),
                                    objectId,
                                    reference.resourceId(),
                                    binding.id(),
                                    recordId,
                                    null,
                                    null),
                            actor);
            var childRef =
                    new PublishedResourceRef(
                            reference.applicationId(),
                            reference.applicationVersion(),
                            reference.applicationChecksum(),
                            binding.formId(),
                            reference.resourceKind());
            var rows = entry.getValue();
            if (rows == null || (count += rows.size()) > 100) throw invalid("关联草稿记录无效或超过 100 条");
            if (!resolved.multiple()
                    && rows.stream().filter(Objects::nonNull).filter(r -> !r.unlink()).count() > 1)
                throw invalid("此关联草稿区域只能选择一条记录");
            var normalized = new ArrayList<RelatedForms.Row>();
            var ids = new HashSet<String>();
            for (var row : rows) {
                if (row == null
                        || row.values() == null
                        || row.details() == null
                        || row.id() != null && !ids.add(row.id())) throw invalid("关联草稿记录无效或重复");
                boolean incoming = RelationDirectionEnum.INCOMING.matches(binding.direction());
                boolean write =
                        incoming
                                || row.id() == null
                                || !row.values().isEmpty()
                                || !row.details().isEmpty();
                var child =
                        access(
                                childRef,
                                resolved.model().object().objectId(),
                                row.id(),
                                actor,
                                write);
                if (!Objects.equals(row.expectedRevision(), child.recordRevision()))
                    throw new ServiceException(CONFLICT, "关联记录已变化，原草稿保留，请比较记录版本后继续");
                if (incoming && !child.permissions().writeFields().contains(resolved.linkFieldId()))
                    throw invalid("关联草稿的归属字段写入权限已变化，原草稿保留");
                if (!child.permissions().readFields().containsAll(row.values().keySet())
                        || !child.permissions().readDetails().containsAll(row.details().keySet()))
                    throw invalid("关联草稿的字段读取权限已变化，原草稿保留，不能裁剪后继续提交");
                var values = write ? validateValues(row.values(), child, true) : row.values();
                var details = write ? validateDetails(row.details(), child, true) : row.details();
                recordService.validateWorkDraftDetails(
                        reference.applicationId(),
                        child.form().objectId(),
                        row.id(),
                        details,
                        actor);
                recordService.validateWorkDraftReferences(
                        reference.applicationId(),
                        child.form().objectId(),
                        row.id(),
                        binding.formId(),
                        values,
                        actor);
                normalized.add(
                        new RelatedForms.Row(
                                row.id(), row.expectedRevision(), values, details, row.unlink()));
            }
            output.put(entry.getKey(), normalized);
        }
        return output;
    }

    private FormAccess access(
            PublishedResourceRef reference,
            String objectId,
            String recordId,
            long actor,
            boolean write) {
        if (!ApplicationResourceKindEnum.FORM.matches(reference.resourceKind()))
            throw invalid("工作草稿必须绑定已发布业务表单");
        var resource = publishedService.resolve(reference);
        var form = resourceValidator.decode(resource.config(), ApplicationUi.Form.class);
        if (!Objects.equals(form.objectId(), objectId)) throw invalid("表单与业务对象不匹配");
        var model = recordService.model(reference.applicationId(), objectId, actor);
        var row =
                recordId == null
                        ? null
                        : recordService
                                .get(reference.applicationId(), objectId, recordId, actor)
                                .record();
        var permissions =
                write
                        ? recordService.workWriteCapabilities(
                                reference.applicationId(), objectId, recordId, actor)
                        : row == null ? model.permissions() : row.permissions();
        if (write
                && (!model.writable()
                        || !permissions
                                .actions()
                                .contains(
                                        (recordId == null
                                                        ? ApplicationActionEnum.CREATE
                                                        : ApplicationActionEnum.UPDATE)
                                                .getCode())))
            throw invalid("没有此记录的草稿写入权限，或记录正在受保护");
        if (write && form.options() != null && Boolean.TRUE.equals(form.options().readOnly()))
            throw invalid("当前表单为只读");
        return new FormAccess(form, model, permissions, row == null ? null : row.revision());
    }

    private Map<String, Object> validateValues(Map<String, Object> input, FormAccess access) {
        return validateValues(input, access, false);
    }

    private Map<String, Object> validateValues(
            Map<String, Object> input, FormAccess access, boolean allowFiles) {
        if (input == null || input.size() > 500) throw invalid("草稿字段无效或过多");
        var presentations = SelectionFields.presentations(access.form().nodes());
        var allowed = new HashSet<>(access.permissions().writeFields());
        allowed.retainAll(presentations.keySet());
        presentations.forEach(
                (id, rule) -> {
                    if (rule != null && Boolean.TRUE.equals(rule.readOnly())) allowed.remove(id);
                });
        if (!allowed.containsAll(input.keySet())) throw invalid("草稿包含无权修改或不属于表单的字段");
        Map<String, Object> normalized = new LinkedHashMap<>();
        input.forEach(
                (id, value) -> {
                    var field =
                            access.model().object().fields().stream()
                                    .filter(f -> f.id().equals(id))
                                    .findFirst()
                                    .orElseThrow(() -> invalid("草稿字段不存在或已撤权"));
                    var type = FieldTypeEnum.fromCode(field.type());
                    if (type.isComputed()
                            || type == FieldTypeEnum.AUTO_NUMBER
                            || BaseDOColumns.NAMES.contains(field.code()))
                        throw invalid("系统字段不能通过草稿修改");
                    var options =
                            access.model()
                                    .object()
                                    .fieldOptions()
                                    .getOrDefault(id, DataCenter.FieldOptions.defaults());
                    if (!allowFiles
                            && (type == FieldTypeEnum.ATTACHMENT || type == FieldTypeEnum.IMAGE)
                            && value != null
                            && (!(value instanceof Collection<?> files) || !files.isEmpty()))
                        throw invalid("当前工作草稿尚未接入附件保留，请使用已有业务保存入口");
                    // convert 只校验已有输入的类型和边界，正式提交仍由 RecordService 校验必填及所有引用。
                    normalized.put(id, recordValues.convert(field, options, value));
                });
        return normalized;
    }

    private Map<String, List<ApplicationRecords.Row>> validateDetails(
            Map<String, List<ApplicationRecords.Row>> input, FormAccess access) {
        return validateDetails(input, access, false);
    }

    private Map<String, List<ApplicationRecords.Row>> validateDetails(
            Map<String, List<ApplicationRecords.Row>> input,
            FormAccess access,
            boolean allowFiles) {
        if (input == null
                || input.size() > 20
                || !access.form().detailIds().containsAll(input.keySet())
                || !access.permissions().writeDetails().containsAll(input.keySet()))
            throw invalid("草稿包含无权修改或不属于表单的明细");
        Map<String, List<ApplicationRecords.Row>> groups = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            var detail =
                    access.model().object().details().stream()
                            .filter(
                                    d ->
                                            d.id().equals(entry.getKey())
                                                    && MemberStateEnum.ACTIVE.matches(d.state()))
                            .findFirst()
                            .orElseThrow(() -> invalid("草稿明细不存在或已停用"));
            if (entry.getValue() == null || entry.getValue().size() > 500)
                throw invalid("每组草稿明细最多 500 行");
            var rows = new ArrayList<ApplicationRecords.Row>();
            Set<String> keys = new HashSet<>();
            for (var row : entry.getValue()) {
                if (row == null || row.values() == null || row.values().size() > 500)
                    throw invalid("草稿明细行格式无效");
                String key =
                        row.clientRowKey() == null
                                ? row.id() == null
                                        ? UUID.randomUUID().toString()
                                        : "row-" + row.id()
                                : row.clientRowKey();
                if (!key.matches("[A-Za-z0-9_-]{1,100}") || !keys.add(key))
                    throw invalid("草稿明细临时行键无效或重复");
                Map<String, Object> values = new LinkedHashMap<>();
                row.values()
                        .forEach(
                                (id, value) -> {
                                    var field =
                                            detail.fields().stream()
                                                    .filter(f -> f.id().equals(id))
                                                    .findFirst()
                                                    .orElseThrow(() -> invalid("草稿明细字段已失效"));
                                    var type = FieldTypeEnum.fromCode(field.type());
                                    if (type.isComputed()
                                            || type == FieldTypeEnum.AUTO_NUMBER
                                            || BaseDOColumns.NAMES.contains(field.code()))
                                        throw invalid("系统字段不能通过草稿修改");
                                    if (!allowFiles
                                            && (type == FieldTypeEnum.ATTACHMENT
                                                    || type == FieldTypeEnum.IMAGE)
                                            && value instanceof Collection<?> files
                                            && !files.isEmpty()) throw invalid("附件草稿保留能力尚未接入");
                                    values.put(
                                            id,
                                            recordValues.convert(
                                                    field,
                                                    detail.fieldOptions()
                                                            .getOrDefault(
                                                                    id,
                                                                    DataCenter.FieldOptions
                                                                            .defaults()),
                                                    value));
                                });
                rows.add(
                        new ApplicationRecords.Row(
                                row.id(), row.revision(), values, null, Map.of(), key));
            }
            groups.put(detail.id(), rows);
        }
        return groups;
    }

    private static Map<String, List<ApplicationRecords.Row>> materialDetails(
            Map<String, List<ApplicationRecords.Row>> groups, Set<String> readable) {
        Map<String, List<ApplicationRecords.Row>> result = new LinkedHashMap<>();
        groups.forEach(
                (id, rows) -> {
                    if (readable.contains(id))
                        result.put(
                                id,
                                rows.stream()
                                        .map(
                                                r ->
                                                        new ApplicationRecords.Row(
                                                                r.id(),
                                                                r.revision(),
                                                                r.values(),
                                                                null,
                                                                r.displayValues(),
                                                                r.clientRowKey()))
                                        .toList());
                });
        return result;
    }

    private static Map<String, String> visibleLabels(
            Map<String, String> labels, Set<String> fields) {
        Map<String, String> result = new LinkedHashMap<>();
        labels.forEach(
                (id, label) -> {
                    if (fields.contains(id)) result.put(id, label);
                });
        return result;
    }

    private Map<String, Object> visible(Map<String, Object> values, Set<String> allowed) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach(
                (id, value) -> {
                    if (allowed.contains(id)) result.put(id, value);
                });
        return result;
    }

    /** 草稿输入中的受保护文件编号：按固定发布版本读取规则，只取接入的附件/图片字段，主表和明细按最终输入收集 */
    private List<Long> businessFileIds(
            DataCenter.Definition definition,
            Map<String, Object> values,
            Map<String, List<ApplicationRecords.Row>> details) {
        DataCenter.BusinessFilePolicy policy =
                definition.settings() == null ? null : definition.settings().businessFilePolicy();
        if (!DataCenter.BusinessFilePolicy.enabled(policy)) return List.of();
        Set<String> fields = new HashSet<>(policy.fieldIds());
        Set<Long> result = new LinkedHashSet<>();
        for (FieldDefinition field : definition.fields()) {
            if (protectedFile(field, fields)) collectFileIds(values.get(field.id()), result);
        }
        for (DataCenter.Detail detail : definition.details()) {
            List<ApplicationRecords.Row> rows = details.get(detail.id());
            if (rows == null) continue;
            for (FieldDefinition field : detail.fields()) {
                if (!protectedFile(field, fields)) continue;
                for (ApplicationRecords.Row row : rows)
                    collectFileIds(row.values().get(field.id()), result);
            }
        }
        return new ArrayList<>(result);
    }

    private boolean protectedFile(FieldDefinition field, Set<String> fields) {
        return fields.contains(field.id())
                && (FieldTypeEnum.ATTACHMENT.matches(field.type())
                        || FieldTypeEnum.IMAGE.matches(field.type()));
    }

    private void collectFileIds(Object value, Set<Long> out) {
        if (value == null) return;
        if (value instanceof Collection<?> items) {
            for (Object item : items) collectFileId(item, out);
            return;
        }
        collectFileId(value, out);
    }

    private void collectFileId(Object value, Set<Long> out) {
        if (value == null) return;
        String text = String.valueOf(value);
        if (text.matches("[1-9][0-9]{0,18}")) out.add(Long.valueOf(text));
    }

    private record FormAccess(
            ApplicationUi.Form form,
            ApplicationRecords.Model model,
            ApplicationAuthorization.Capabilities permissions,
            String recordRevision) {}
}
