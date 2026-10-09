package com.lingan.ucp.framework.job.core.handler;

import com.lingan.ucp.framework.common.util.json.JsonUtils;
import com.lingan.ucp.framework.job.core.service.JobLogFrameworkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BasicProcessor;

import java.time.Duration;
import java.time.LocalDateTime;

import static cn.hutool.core.exceptions.ExceptionUtil.getRootCauseMessage;

/**
 * PowerJob 通用处理器，负责路由到业务 {@link JobHandler} 并记录本地任务日志。
 *
 * <p>仅承载单机任务。并发控制与重试次数由 PowerJob Server 管理，重试间隔在每次重试执行前兼容。</p>
 */
@Slf4j
public class JobHandlerProcessor implements BasicProcessor {

    public static final String PROCESSOR_BEAN_NAME = "osJobHandlerProcessor";

    private final ApplicationContext applicationContext;
    private final JobLogFrameworkService jobLogFrameworkService;

    public JobHandlerProcessor(ApplicationContext applicationContext,
                               JobLogFrameworkService jobLogFrameworkService) {
        this.applicationContext = applicationContext;
        this.jobLogFrameworkService = jobLogFrameworkService;
    }

    @Override
    public ProcessResult process(TaskContext context) {
        LocalDateTime startTime = LocalDateTime.now();
        JobExecutionParam executionParam;
        Long jobLogId = null;
        String result = null;
        Throwable exception = null;
        try {
            executionParam = JsonUtils.parseObject(context.getJobParams(), JobExecutionParam.class);
            validateExecutionParam(executionParam);
            waitBeforeRetry(context, executionParam.retryInterval());
            jobLogId = jobLogFrameworkService.createJobLog(executionParam.jobId(), startTime,
                    executionParam.handlerName(), executionParam.handlerParam(), context.getCurrentRetryTimes() + 1);
            JobHandler jobHandler = applicationContext.getBean(executionParam.handlerName(), JobHandler.class);
            result = jobHandler.execute(executionParam.handlerParam());
        } catch (Throwable ex) {
            exception = ex;
        }

        updateJobLog(jobLogId, startTime, result, exception, context);
        if (exception == null) {
            return new ProcessResult(true, result);
        }
        String errorMessage = getRootCauseMessage(exception);
        context.getOmsLogger().error("JobHandler execution failed, jobId={}, instanceId={}",
                context.getJobId(), context.getInstanceId(), exception);
        return new ProcessResult(false, errorMessage);
    }

    private void validateExecutionParam(JobExecutionParam executionParam) {
        if (executionParam == null || executionParam.jobId() == null
                || executionParam.handlerName() == null || executionParam.handlerName().isBlank()) {
            throw new IllegalArgumentException("PowerJob 任务路由参数不完整");
        }
    }

    private void waitBeforeRetry(TaskContext context, Integer retryInterval) throws InterruptedException {
        if (context.getCurrentRetryTimes() <= 0 || retryInterval == null || retryInterval <= 0) {
            return;
        }
        try {
            Thread.sleep(retryInterval);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        }
    }

    private void updateJobLog(Long jobLogId, LocalDateTime startTime, String result, Throwable exception,
                              TaskContext context) {
        if (jobLogId == null) {
            return;
        }
        LocalDateTime endTime = LocalDateTime.now();
        boolean success = exception == null;
        String logResult = success ? result : getRootCauseMessage(exception);
        long durationMillis = Duration.between(startTime, endTime).toMillis();
        int duration = (int) Math.min(durationMillis, Integer.MAX_VALUE);
        try {
            jobLogFrameworkService.updateJobLogResultAsync(jobLogId, endTime, duration, success, logResult);
        } catch (Exception ex) {
            log.error("[updateJobLog][powerJobId({}) instanceId({}) logId({}) 记录执行结果失败]",
                    context.getJobId(), context.getInstanceId(), jobLogId, ex);
        }
    }
}
