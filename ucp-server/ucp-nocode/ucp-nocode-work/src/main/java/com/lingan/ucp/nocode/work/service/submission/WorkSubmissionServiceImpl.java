package com.lingan.ucp.nocode.work.service.submission;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.crypto.digest.DigestUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.work.WorkDrafts;
import com.lingan.ucp.nocode.work.dal.dataobject.submission.WorkSubmissionDO;
import com.lingan.ucp.nocode.work.dal.mapper.submission.WorkSubmissionMapper;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/** 材料只追加；幂等结果读取仍须由上层执行实时业务授权。 */
@Service
public class WorkSubmissionServiceImpl implements WorkSubmissionService {
    @Override
    public WorkDrafts.Submission flowMaterial(String id, String sourceTaskId, long submitter) {
        if (submitter <= 0 || sourceTaskId == null || id == null || !id.matches("[a-f0-9-]{36}"))
            throw invalid("提交材料不存在或无权访问");
        var row =
                submissionMapper.flowMaterial(
                        id,
                        sourceTaskId,
                        Long.toString(submitter),
                        com.lingan.ucp.nocode.enums.WorkSourceEnum.FLOW_TASK.getCode());
        if (row == null) throw invalid("提交材料不存在或无权访问");
        return read(row);
    }

    @Resource private WorkSubmissionMapper submissionMapper;
    @Resource private ObjectMapper json;

    @Override
    public boolean protectsFlowRecord(String objectId, String recordId) {
        return submissionMapper.protectsRecord(
                objectId, recordId, com.lingan.ucp.nocode.enums.WorkSourceEnum.FLOW_TASK.getCode());
    }

    @Override
    public WorkDrafts.Submission forDraft(String draftId, long actor) {
        if (actor <= 0 || draftId == null || !draftId.matches("[a-f0-9-]{36}"))
            throw invalid("工作草稿不存在或无权访问");
        var row = submissionMapper.byDraft(draftId, Long.toString(actor));
        return row == null ? null : read(row);
    }

    @Override
    public WorkDrafts.Submission findSuccessful(WorkDrafts.Submit command, long actor) {
        validate(command, actor);
        requireTransaction();
        submissionMapper.lockCommand(
                "nocode-work-submit:" + actor + ":" + command.idempotencyKey());
        var existing = submissionMapper.byCommand(Long.toString(actor), command.idempotencyKey());
        if (existing == null) return null;
        if (!Objects.equals(existing.getRequestDigest(), digest(command)))
            throw invalid("同一个幂等标识不能用于不同的提交内容");
        return read(existing);
    }

    @Override
    public WorkDrafts.Submission create(
            WorkDrafts.Submit command, WorkDrafts.Submission material, long actor) {
        validate(command, actor);
        requireTransaction();
        if (material == null || !Objects.equals(material.draftId(), command.draftId()))
            throw invalid("提交材料与草稿不一致");
        var row = new WorkSubmissionDO();
        row.setId(material.id());
        row.setDraftId(command.draftId());
        row.setIdempotencyKey(command.idempotencyKey());
        row.setRequestDigest(digest(command));
        try {
            row.setMaterialJson(json.writeValueAsString(material));
        } catch (java.io.IOException e) {
            throw invalid("提交材料无法封存");
        }
        submissionMapper.create(row, Long.toString(actor));
        return get(material.id(), actor);
    }

    @Override
    public WorkDrafts.Submission get(String id, long actor) {
        if (actor <= 0 || id == null || !id.matches("[a-f0-9-]{36}")) throw invalid("提交材料不存在或无权访问");
        var row = submissionMapper.owned(id, Long.toString(actor));
        if (row == null) throw invalid("提交材料不存在或无权访问");
        return read(row);
    }

    private WorkDrafts.Submission read(WorkSubmissionDO row) {
        try {
            var material = json.readValue(row.getMaterialJson(), WorkDrafts.Submission.class);
            return new WorkDrafts.Submission(
                    material.id(),
                    material.draftId(),
                    material.resource(),
                    material.objectId(),
                    material.recordId(),
                    material.recordRevision(),
                    material.values(),
                    row.getCreateTime(),
                    material.details(),
                    material.displayValues(),
                    material.policyVersion(),
                    material.handling());
        } catch (java.io.IOException e) {
            throw invalid("提交材料无法读取");
        }
    }

    private void validate(WorkDrafts.Submit command, long actor) {
        if (actor <= 0
                || command == null
                || command.draftId() == null
                || !command.draftId().matches("[a-f0-9-]{36}")
                || command.expectedRevision() < 0
                || command.idempotencyKey() == null
                || !command.idempotencyKey().matches("[A-Za-z0-9:_-]{8,128}"))
            throw invalid("提交标识或草稿修订无效");
    }

    private String digest(WorkDrafts.Submit command) {
        // 兼容旧的无状态动作提交摘要；配置动作也是提交意图的一部分。
        String identity = command.draftId() + ":" + command.expectedRevision();
        return DigestUtil.sha256Hex(
                command.actionCode() == null ? identity : identity + ":" + command.actionCode());
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("提交材料必须与业务数据处于同一事务");
    }
}
