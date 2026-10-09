package com.richuang.os.nocode.runtime.service.bizfile;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.core.collection.CollUtil;

import com.richuang.os.module.drive.api.bizfile.DriveBizFileApi;
import com.richuang.os.nocode.runtime.dal.dataobject.BizUploadSessionDO;
import com.richuang.os.nocode.runtime.dal.mapper.BizUploadSessionMapper;
import com.richuang.os.nocode.runtime.service.task.TaskEntryRuntimeScope;

import jakarta.annotation.Resource;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务附件上传会话 Service
 *
 * <p>仅业务接入字段使用：上传阶段登记临时会话（有效期 24 小时），保存阶段校验归属并标记绑定。 普通系统附件沿用既有上传链路，不经本服务登记。
 */
@Service
public class BizUploadSessionService {

    /** 临时上传默认有效期（小时） */
    private static final int TEMPORARY_HOURS = 24;

    @Resource private BizUploadSessionMapper sessions;
    @Resource private DriveBizFileApi driveFiles;
    @Resource private BizUploadCleanupService cleanup;
    @Resource private TaskEntryRuntimeScope taskScope;

    /**
     * 上传受保护文件并登记临时会话
     *
     * @return 文件内容编号（infra_file.id）
     */
    public Long uploadTemporary(
            long actor,
            String objectId,
            String fieldId,
            String recordId,
            String sessionKey,
            String idempotencyKey,
            String fileName,
            String contentType,
            long size,
            InputStream content) {
        String key = requireKey(sessionKey, 64, "上传会话标识无效");
        TaskBusinessFileSessions.require(taskScope, key, recordId);
        String idem =
                idempotencyKey == null || idempotencyKey.isBlank()
                        ? ""
                        : requireKey(idempotencyKey, 128, "上传幂等键无效");
        if (!idem.isEmpty()) {
            Long existed = idempotentFile(actor, objectId, fieldId, key, idem);
            if (existed != null) {
                return existed;
            }
        }
        Long fileId = driveFiles.uploadProtectedContent(fileName, contentType, size, content);
        BizUploadSessionDO session = new BizUploadSessionDO();
        session.setSessionKey(key);
        session.setUserId(actor);
        session.setObjectId(objectId);
        session.setFieldId(fieldId);
        session.setRecordId(recordId);
        session.setFileId(fileId);
        session.setFileName(fileName);
        session.setState(BizUploadSessionDO.STATE_TEMPORARY);
        session.setExpiresAt(LocalDateTime.now().plusHours(TEMPORARY_HOURS));
        session.setIdempotencyKey(idem);
        session.setCreator(Long.toString(actor));
        session.setUpdater(Long.toString(actor));
        try {
            sessions.insert(session);
            return fileId;
        } catch (DuplicateKeyException concurrentRetry) {
            compensateUnregistered(fileId, fileName, concurrentRetry);
            Long winner = idempotentFile(actor, objectId, fieldId, key, idem);
            if (winner != null) {
                return winner;
            }
            throw concurrentRetry;
        } catch (RuntimeException registrationFailure) {
            compensateUnregistered(fileId, fileName, registrationFailure);
            throw registrationFailure;
        }
    }

    /**
     * 校验保存新增文件均来自当前用户在本对象字段上的有效临时会话
     *
     * <p>防止提交他人上传的 fileId 或绕过会话直接引用文件底座内容（A12）。
     *
     * @return 会话编号列表，保存成功后用于标记绑定
     */
    public List<Long> validateOwnership(
            long actor, String objectId, String fieldId, List<Long> newFileIds) {
        return validateOwnership(actor, objectId, fieldId, null, newFileIds);
    }

    /** 任务专属临时文件必须回到原任务入口；新建上传没有 recordId，允许首次保存生成记录身份。 */
    public List<Long> validateOwnership(
            long actor, String objectId, String fieldId, String recordId, List<Long> newFileIds) {
        if (CollUtil.isEmpty(newFileIds)) {
            return List.of();
        }
        List<BizUploadSessionDO> matched =
                sessions.selectTemporaryList(objectId, fieldId, newFileIds);
        Map<Long, BizUploadSessionDO> byFile = new LinkedHashMap<>();
        for (BizUploadSessionDO session : matched) {
            byFile.put(session.getFileId(), session);
        }
        List<Long> sessionIds = new ArrayList<>(newFileIds.size());
        for (Long fileId : newFileIds) {
            BizUploadSessionDO session = byFile.get(fileId);
            if (session == null || session.getUserId() != actor) {
                throw invalid("文件未经当前表单上传会话登记或已过期，请重新上传");
            }
            TaskBusinessFileSessions.require(
                    taskScope, session.getSessionKey(), session.getRecordId());
            if (TaskBusinessFileSessions.scoped(session.getSessionKey())
                    && session.getRecordId() != null
                    && !session.getRecordId().equals(recordId))
                throw invalid("附件上传会话不属于当前业务记录，请重新上传");
            sessionIds.add(session.getId());
        }
        int claimed = sessions.claimBinding(sessionIds, Long.toString(actor));
        if (claimed != sessionIds.size()) {
            throw invalid("文件上传会话已过期或正在被其他请求保存，请刷新后重试");
        }
        return sessionIds;
    }

    /** 保存成功后标记会话已绑定；后续不再按临时规则清理 */
    public void markBound(List<Long> sessionIds, long actor) {
        if (CollUtil.isEmpty(sessionIds)) {
            return;
        }
        int bound = sessions.markBound(sessionIds, Long.toString(actor));
        if (bound != sessionIds.size()) {
            throw invalid("文件上传会话状态已变化，保存已取消，请重试");
        }
    }

    /** 有效编辑会话续期：本人该会话键下的全部临时上传顺延有效期；会话已过期时不复活，由用户重新上传 */
    public boolean renew(String sessionKey, long actor) {
        String key = requireKey(sessionKey, 64, "上传会话标识无效");
        if (TaskBusinessFileSessions.scoped(key) && !taskScope.delegated())
            throw invalid("任务附件必须从当前任务办理项续期");
        taskScope.requireWrite();
        return sessions.renew(
                        key,
                        actor,
                        LocalDateTime.now().plusHours(TEMPORARY_HOURS),
                        Long.toString(actor))
                > 0;
    }

    /**
     * 草稿引用续留：本人该对象下被草稿引用的临时上传顺延有效期
     *
     * <p>草稿存续期间反复暂存即反复续留；会话已过期时不复活（A21），内容仍由保留引用阻止清理。
     */
    public int renewFiles(String objectId, Collection<Long> fileIds, long actor) {
        if (objectId == null || CollUtil.isEmpty(fileIds)) {
            return 0;
        }
        List<Long> files = new ArrayList<>();
        for (Long fileId : fileIds) {
            if (fileId != null && fileId > 0) {
                files.add(fileId);
            }
        }
        if (files.isEmpty()) {
            return 0;
        }
        return sessions.renewByFiles(
                objectId,
                files,
                actor,
                LocalDateTime.now().plusHours(TEMPORARY_HOURS),
                Long.toString(actor));
    }

    /**
     * 会话内临时内容读取依据
     *
     * <p>仅上传者本人在有效期内、仍是临时状态的会话文件可读；不使用业务记录授权， 保存成功后内容改由业务内容端点按完整授权链读取。
     *
     * @return 匹配的会话登记（fileId 与登记一致）
     */
    public BizUploadSessionDO requireTemporary(
            long actor, String objectId, String fieldId, String sessionKey, Long fileId) {
        String key = requireKey(sessionKey, 64, "上传会话标识无效");
        if (fileId == null || fileId <= 0) {
            throw invalid("临时文件编号无效");
        }
        // 查询已限定临时状态与未过期；此处再核对会话键归属，避免凭他人会话键读取
        List<BizUploadSessionDO> matched =
                sessions.selectTemporaryList(objectId, fieldId, List.of(fileId));
        for (BizUploadSessionDO session : matched) {
            if (session.getUserId() == actor && key.equals(session.getSessionKey())) {
                TaskBusinessFileSessions.require(taskScope, key, session.getRecordId());
                return session;
            }
        }
        throw invalid("临时文件不存在或上传会话已过期，请重新上传");
    }

    private Long idempotentFile(
            long actor, String objectId, String fieldId, String sessionKey, String idempotencyKey) {
        BizUploadSessionDO existed =
                sessions.selectIdempotent(actor, objectId, fieldId, sessionKey, idempotencyKey);
        return existed == null ? null : existed.getFileId();
    }

    /** 内容已写入但会话登记失败：立即回收，回收失败则登记可重试任务。 */
    private void compensateUnregistered(
            Long fileId, String fileName, RuntimeException registrationFailure) {
        try {
            driveFiles.deleteTemporaryContent(fileId);
        } catch (RuntimeException deleteFailure) {
            registrationFailure.addSuppressed(deleteFailure);
            cleanup.recordUnregisteredFile(fileId, fileName, deleteFailure);
        }
    }

    private String requireKey(String value, int maxLength, String message) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()
                || trimmed.length() > maxLength
                || !trimmed.matches("[A-Za-z0-9_-]+")) {
            throw invalid(message);
        }
        return trimmed;
    }
}
