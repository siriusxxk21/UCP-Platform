package com.richuang.os.nocode.runtime.service.bizfile;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;

import com.richuang.os.framework.common.util.json.JsonUtils;
import com.richuang.os.module.drive.api.bizfile.DriveBizFileApi;
import com.richuang.os.nocode.runtime.dal.dataobject.BizFileTaskDO;
import com.richuang.os.nocode.runtime.dal.dataobject.BizUploadSessionDO;
import com.richuang.os.nocode.runtime.dal.mapper.BizFileTaskMapper;
import com.richuang.os.nocode.runtime.dal.mapper.BizUploadSessionMapper;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 过期临时上传清理 Service
 *
 * <p>只按上传会话表驱动：过期临时会话先标记待清理，再逐文件复核有效保留引用与受管节点， 无引用内容执行物理删除并将会话登记为已清理；删除失败登记可重试的文件操作任务，后续执行继续补偿。
 * 不扫描文件底座全表，普通系统附件不纳入自动清理。
 */
@Slf4j
@Service
public class BizUploadCleanupService {

    /** 任务类型：临时内容物理删除补偿 */
    public static final String TASK_TYPE_TEMP_CONTENT_DELETE = "TEMP_CONTENT_DELETE";

    /** 清理动作的登记人：定时任务没有业务用户，用任务名溯源 */
    static final String CLEANUP_ACTOR = "bizUploadCleanJob";

    /** 错误摘要存储上限，与 nocode_biz_file_task.last_error 列宽一致 */
    private static final int LAST_ERROR_MAX_LENGTH = 2000;

    @Resource private BizUploadSessionMapper sessions;
    @Resource private BizFileRetentionService retentions;
    @Resource private BizFileTaskMapper fileTasks;
    @Resource private DriveBizFileApi driveFiles;

    /**
     * 执行一次过期临时上传清理
     *
     * <p>两阶段推进：先按批把超过有效期的临时会话标记为待清理（过期不复活，保存与续期都不再接受）； 再按批对待清理会话逐文件复核保留引用，无有效引用的内容删除并登记会话已清理。
     * 复核在删除前重查，避免文件在等待清理期间被草稿或历史重新引用后误删。
     *
     * @param batchLimit 单次执行的批大小上限；避免一次执行长时间占用
     * @return 本次执行统计
     */
    public BizUploadCleanupResult cleanupExpired(int batchLimit) {
        // 一、过期标记：待保存的过期会话进入待清理，只等内容删除或保留解除
        List<BizUploadSessionDO> expired = sessions.selectExpiredList(batchLimit);
        int expiredMarked =
                CollUtil.isEmpty(expired) ? 0 : sessions.markExpired(idsOf(expired), CLEANUP_ACTOR);
        // 二、清理执行：待清理会话按文件聚合，复核对整个文件只做一次
        List<BizUploadSessionDO> pending = sessions.selectCleanupList(batchLimit);
        if (CollUtil.isEmpty(pending)) {
            int[] retried = retryOpenTasks(batchLimit);
            return new BizUploadCleanupResult(expiredMarked, retried[0], 0, retried[1], retried[2]);
        }
        Map<Long, List<BizUploadSessionDO>> sessionsByFile = new LinkedHashMap<>();
        for (BizUploadSessionDO session : pending) {
            sessionsByFile
                    .computeIfAbsent(session.getFileId(), key -> new ArrayList<>())
                    .add(session);
        }
        Set<Long> retained = retentions.retainedFileIds(sessionsByFile.keySet());
        int filesCleaned = 0;
        int sessionsCleaned = 0;
        int skipped = 0;
        int failed = 0;
        for (Map.Entry<Long, List<BizUploadSessionDO>> entry : sessionsByFile.entrySet()) {
            Long fileId = entry.getKey();
            List<BizUploadSessionDO> fileSessions = entry.getValue();
            // 复核：历史/草稿/材料仍持有，或受管节点仍引用时不得删除内容，会话保持待清理
            if (retained.contains(fileId) || driveFiles.hasManagedEntry(fileId)) {
                skipped++;
                continue;
            }
            try {
                driveFiles.deleteTemporaryContent(fileId);
                sessionsCleaned += sessions.markCleaned(idsOf(fileSessions), CLEANUP_ACTOR);
                fileTasks.markDone(TASK_TYPE_TEMP_CONTENT_DELETE, fileId, CLEANUP_ACTOR);
                filesCleaned++;
            } catch (RuntimeException exception) {
                failed++;
                recordFailure(
                        fileId,
                        fileSessions.getFirst().getFileName(),
                        idsOf(fileSessions),
                        exception);
            }
        }
        int[] retried = retryOpenTasks(batchLimit);
        return new BizUploadCleanupResult(
                expiredMarked,
                filesCleaned + retried[0],
                sessionsCleaned,
                skipped + retried[1],
                failed + retried[2]);
    }

    /** 会话登记失败后的内容无会话行可扫描，因此独立重试文件任务。 */
    private int[] retryOpenTasks(int batchLimit) {
        int cleaned = 0;
        int skipped = 0;
        int failed = 0;
        for (BizFileTaskDO task :
                fileTasks.selectOpenList(TASK_TYPE_TEMP_CONTENT_DELETE, batchLimit)) {
            Long fileId = task.getFileId();
            if (fileId == null) {
                fileTasks.markFailed(task.getId(), "补偿任务缺少文件编号", CLEANUP_ACTOR);
                failed++;
                continue;
            }
            if (retentions.retainedFileIds(Set.of(fileId)).contains(fileId)
                    || driveFiles.hasManagedEntry(fileId)) {
                skipped++;
                continue;
            }
            try {
                driveFiles.deleteTemporaryContent(fileId);
                fileTasks.markDone(TASK_TYPE_TEMP_CONTENT_DELETE, fileId, CLEANUP_ACTOR);
                cleaned++;
            } catch (RuntimeException exception) {
                recordFailure(fileId, "", List.of(), exception);
                failed++;
            }
        }
        return new int[] {cleaned, skipped, failed};
    }

    /** 上传内容已写入、会话登记失败且立即回收也失败时登记补偿。 */
    public void recordUnregisteredFile(Long fileId, String fileName, RuntimeException exception) {
        recordFailure(fileId, StrUtil.emptyIfNull(fileName), List.of(), exception);
    }

    /** 失败登记：同一文件复用未完成的任务行累加尝试次数，保留最后一次错误 */
    private void recordFailure(
            Long fileId, String fileName, List<Long> sessionIds, RuntimeException exception) {
        String message = exception.getMessage();
        String lastError =
                message == null || message.isBlank()
                        ? exception.getClass().getSimpleName()
                        : message;
        if (lastError.length() > LAST_ERROR_MAX_LENGTH) {
            lastError = lastError.substring(0, LAST_ERROR_MAX_LENGTH);
        }
        BizFileTaskDO open = fileTasks.selectOpenByFile(TASK_TYPE_TEMP_CONTENT_DELETE, fileId);
        if (open != null) {
            fileTasks.markFailed(open.getId(), lastError, CLEANUP_ACTOR);
        } else {
            BizFileTaskDO task = new BizFileTaskDO();
            task.setTaskType(TASK_TYPE_TEMP_CONTENT_DELETE);
            task.setFileId(fileId);
            task.setState(BizFileTaskDO.STATE_FAILED);
            task.setAttempts(1);
            task.setLastError(lastError);
            task.setPayloadJson(
                    JsonUtils.toJsonString(Map.of("fileName", fileName, "sessionIds", sessionIds)));
            task.setCreator(CLEANUP_ACTOR);
            task.setUpdater(CLEANUP_ACTOR);
            fileTasks.insertTask(task);
        }
        log.warn(
                "[recordFailure][临时内容删除失败登记补偿：fileId={} fileName={} error={}]",
                fileId,
                fileName,
                lastError);
    }

    private List<Long> idsOf(List<BizUploadSessionDO> sessions) {
        List<Long> ids = new ArrayList<>(sessions.size());
        for (BizUploadSessionDO session : sessions) {
            ids.add(session.getId());
        }
        return ids;
    }
}
