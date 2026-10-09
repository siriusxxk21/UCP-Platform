package com.lingan.ucp.nocode.runtime.job.bizfile;

import com.lingan.ucp.framework.job.core.handler.JobHandler;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizUploadCleanupResult;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizUploadCleanupService;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

/**
 * 过期临时上传清理 Job
 *
 * <p>只扫描业务附件上传会话表：超过有效期的临时会话先标记待清理，再复核保留引用与受管节点， 无引用内容执行物理删除并完成登记；失败登记为补偿任务，下次执行继续重试。 管理端定时任务以
 * handler 名称 bizUploadCleanJob 注册触发。
 */
@Component
@Slf4j
public class BizUploadCleanJob implements JobHandler {

    /** 单次执行处理的会话上限，避免一次性长时间占用；剩余部分由后续执行按批推进 */
    private static final int BATCH_LIMIT = 200;

    @Resource private BizUploadCleanupService cleanupService;

    @Override
    public String execute(String param) {
        BizUploadCleanupResult result = cleanupService.cleanupExpired(BATCH_LIMIT);
        log.info(
                "[execute][过期临时上传清理：标记 {} 个，文件清理 {} 个，完成会话 {} 个，保留暂缓 {} 个，失败 {} 个]",
                result.expiredMarked(),
                result.filesCleaned(),
                result.sessionsCleaned(),
                result.skipped(),
                result.failed());
        return String.format(
                "过期标记 %d 个，文件清理 %d 个，完成会话 %d 个，保留暂缓 %d 个，失败 %d 个",
                result.expiredMarked(),
                result.filesCleaned(),
                result.sessionsCleaned(),
                result.skipped(),
                result.failed());
    }
}
