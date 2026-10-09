package com.lingan.ucp.nocode.runtime.job.application;

import com.lingan.ucp.framework.job.core.handler.JobHandler;
import com.lingan.ucp.nocode.application.service.application.ApplicationFollowService;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

/**
 * 应用自动跟随重试 Job
 *
 * <p>处理「跟随待处理」的应用：对象发布时因在途审批、发布校验未通过或系统错误没跟上的，每次最多处理一批。 先不拿锁看在途审批数量，为 0
 * 才进入与人工发布相同的加锁流程，仍在途的不去占独占锁。管理端定时任务以 handler 名称 applicationFollowRetryJob 注册触发。
 */
@Component
@Slf4j
public class ApplicationFollowRetryJob implements JobHandler {

    /** 单次执行处理的待处理行上限；剩余部分由后续执行按批推进 */
    private static final int BATCH_LIMIT = 50;

    @Resource private ApplicationFollowService follows;

    @Override
    public String execute(String param) {
        ApplicationFollowService.Retried result = follows.retryPending(BATCH_LIMIT);
        log.info("[execute][应用自动跟随重试：处理 {} 行，跟上 {} 行]", result.examined(), result.followed());
        return String.format("处理 %d 行，跟上 %d 行", result.examined(), result.followed());
    }
}
