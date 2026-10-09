package com.richuang.os.nocode.work.service.draft;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.work.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.work.dal.dataobject.draft.WorkDraftDO;
import com.richuang.os.nocode.work.dal.mapper.draft.WorkDraftMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 个人业务表单草稿；更新不能改变固定资源、对象或原记录，提交后不可覆盖。 */
@Service
public class WorkDraftServiceImpl implements WorkDraftService {
    @Resource private WorkDraftMapper draftMapper;
    @Resource private ObjectMapper json;
    @Resource private PlatformTransactionManager transactionManager;
    private TransactionTemplate transaction;

    @PostConstruct
    void initialize() {
        transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<WorkDraftViews.Candidate> candidates(WorkDraftViews.Query query, long actor) {
        if (actor <= 0
                || query == null
                || query.applicationId() == null
                || !query.applicationId().matches("[1-9][0-9]{0,18}")
                || query.limit() < 1
                || query.limit() > 50) throw invalid("草稿列表参数无效");
        if (Arrays.stream(WorkDraftStateEnum.values())
                .noneMatch(state -> state.matches(query.state()))) throw invalid("草稿列表状态无效");
        if (query.before() != null
                && (query.before().createdAt() == null
                        || query.before().id() == null
                        || !query.before().id().matches("[a-f0-9-]{36}")))
            throw invalid("草稿列表游标无效");
        if (query.before() != null) {
            try {
                java.time.LocalDateTime.parse(query.before().createdAt());
            } catch (java.time.format.DateTimeParseException invalidTime) {
                throw invalid("草稿列表游标时间无效");
            }
        }
        return draftMapper.candidates(
                query,
                Long.toString(actor),
                WorkSourceEnum.BUSINESS_FORM.getCode(),
                query.limit() + 1);
    }

    @Override
    public List<WorkDraftViews.SourceCandidate> flowTaskCandidates(
            com.richuang.os.nocode.api.workflow.FlowTaskWorkViews.Query query, long actor) {
        if (query == null) throw invalid("流程工作列表参数无效");
        return sourceCandidates(
                new WorkDraftViews.SourceQuery(query.state(), query.before(), query.limit()),
                actor,
                WorkSourceEnum.FLOW_TASK);
    }

    @Override
    public List<WorkDraftViews.SourceCandidate> taskEntryCandidates(
            WorkDraftViews.SourceQuery query, long actor) {
        if (query == null || !WorkDraftStateEnum.DRAFT.matches(query.state()))
            throw invalid("入口草稿列表参数无效");
        return sourceCandidates(query, actor, WorkSourceEnum.TASK_ENTRY);
    }

    private List<WorkDraftViews.SourceCandidate> sourceCandidates(
            WorkDraftViews.SourceQuery query, long actor, WorkSourceEnum source) {
        if (actor <= 0 || query == null || query.limit() < 1 || query.limit() > 50)
            throw invalid("流程工作列表参数无效");
        if (!WorkDraftStateEnum.DRAFT.matches(query.state())
                && !WorkDraftStateEnum.SUBMITTED.matches(query.state()))
            throw invalid("流程工作列表状态无效");
        if (query.before() != null) {
            if (query.before().createdAt() == null
                    || query.before().id() == null
                    || !query.before().id().matches("[a-f0-9-]{36}")) throw invalid("流程工作列表游标无效");
            try {
                java.time.LocalDateTime.parse(query.before().createdAt());
            } catch (java.time.format.DateTimeParseException invalidTime) {
                throw invalid("流程工作列表游标时间无效");
            }
        }
        return draftMapper.sourceCandidates(
                query, Long.toString(actor), source.getCode(), query.limit() + 1);
    }

    @Override
    public String currentSourceDraft(WorkSourceRef source, long actor) {
        if (source == null || source.type() == WorkSourceEnum.BUSINESS_FORM || actor <= 0)
            throw invalid("工作来源无效");
        return draftMapper.currentSourceDraft(
                Long.toString(actor),
                source.type().getCode(),
                source.id(),
                WorkDraftStateEnum.DRAFT.getCode());
    }

    @Override
    public WorkDrafts.Draft save(WorkDrafts.Save command, long actor, WorkSourceRef source) {
        if (source == null
                || actor <= 0
                || command == null
                || command.resource() == null
                || command.objectId() == null
                || command.objectId().length() > 128
                || command.values() == null
                || command.values().size() > 500) throw invalid("工作草稿参数无效");
        if (command.recordId() != null && command.recordId().length() > 512
                || command.baseRecordRevision() != null
                        && command.baseRecordRevision().length() > 256) throw invalid("工作草稿记录引用无效");
        var payload = encode(command.values());
        var detailsPayload = encode(command.details());
        var relatedPayload = encode(command.relatedRecords());
        if (payload.length() + detailsPayload.length() + relatedPayload.length() > 2_000_000)
            throw invalid("工作草稿内容超过大小限制");
        return transaction.execute(
                status -> {
                    if (command.id() == null) {
                        if (command.expectedRevision() != null) throw invalid("新草稿不能指定修订号");
                        var row = new WorkDraftDO();
                        row.setId(UUID.randomUUID().toString());
                        row.setSourceType(source.type().getCode());
                        row.setSourceId(source.sourceId(row.getId()));
                        row.setState(WorkDraftStateEnum.DRAFT.getCode());
                        row.setResourceJson(encode(command.resource()));
                        row.setObjectId(command.objectId());
                        row.setRecordId(command.recordId());
                        row.setBaseRecordRevision(command.baseRecordRevision());
                        row.setValuesJson(payload);
                        row.setDetailsJson(detailsPayload);
                        row.setRelatedJson(relatedPayload);
                        draftMapper.create(row, Long.toString(actor));
                        return get(row.getId(), actor, source);
                    }
                    var row = owned(command.id(), actor, source);
                    var before = view(row);
                    if (!Objects.equals(before.resource(), command.resource())
                            || !Objects.equals(before.objectId(), command.objectId())
                            || !Objects.equals(before.recordId(), command.recordId()))
                        throw invalid("不能更换工作草稿的业务资源或记录");
                    if (command.expectedRevision() == null
                            || !Objects.equals(command.expectedRevision(), row.getLockVersion())
                            || !WorkDraftStateEnum.DRAFT.matches(row.getState())) throw conflict();
                    row.setValuesJson(payload);
                    row.setDetailsJson(detailsPayload);
                    row.setRelatedJson(relatedPayload);
                    row.setBaseRecordRevision(command.baseRecordRevision());
                    if (draftMapper.save(
                                    row,
                                    Long.toString(actor),
                                    command.expectedRevision(),
                                    WorkDraftStateEnum.DRAFT.getCode())
                            != 1) throw conflict();
                    return get(row.getId(), actor, source);
                });
    }

    @Override
    public WorkDrafts.Draft get(String id, long actor, WorkSourceRef source) {
        return transaction.execute(status -> view(owned(id, actor, source)));
    }

    @Override
    public void markSubmitted(String id, int expectedRevision, long actor, WorkSourceRef source) {
        transaction.executeWithoutResult(
                status -> {
                    owned(id, actor, source);
                    if (draftMapper.transition(
                                    id,
                                    Long.toString(actor),
                                    expectedRevision,
                                    WorkDraftStateEnum.DRAFT.getCode(),
                                    WorkDraftStateEnum.SUBMITTED.getCode())
                            != 1) throw conflict();
                });
    }

    private WorkDraftDO owned(String id, long actor, WorkSourceRef source) {
        if (actor <= 0 || id == null || !id.matches("[a-f0-9-]{36}")) throw invalid("工作草稿不存在或无权访问");
        var row = draftMapper.lockOwned(id, Long.toString(actor));
        if (source == null
                || row == null
                || !source.type().matches(row.getSourceType())
                || !Objects.equals(source.sourceId(id), row.getSourceId()))
            throw invalid("工作草稿不存在或无权访问");
        return row;
    }

    private WorkDrafts.Draft view(WorkDraftDO row) {
        try {
            return new WorkDrafts.Draft(
                    row.getId(),
                    row.getLockVersion(),
                    row.getState(),
                    json.readValue(row.getResourceJson(), PublishedResourceRef.class),
                    row.getObjectId(),
                    row.getRecordId(),
                    row.getBaseRecordRevision(),
                    json.readValue(
                            row.getValuesJson(), new TypeReference<Map<String, Object>>() {}),
                    row.getUpdateTime(),
                    json.readValue(
                            row.getDetailsJson(),
                            new TypeReference<
                                    Map<
                                            String,
                                            List<
                                                    com.richuang.os.nocode.api.ApplicationRecords
                                                            .Row>>>() {}),
                    json.readValue(
                            Objects.toString(row.getRelatedJson(), "{}"),
                            new TypeReference<
                                    Map<
                                            String,
                                            List<
                                                    com.richuang.os.nocode.api.RelatedForms
                                                            .Row>>>() {}));
        } catch (java.io.IOException e) {
            throw invalid("工作草稿内容无法读取");
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw invalid("工作草稿内容无法保存");
        }
    }

    private ServiceException conflict() {
        return new ServiceException(CONFLICT, "工作草稿已被修改或提交，请刷新后重试");
    }
}
