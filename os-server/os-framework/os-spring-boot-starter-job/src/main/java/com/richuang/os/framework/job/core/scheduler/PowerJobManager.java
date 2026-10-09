package com.richuang.os.framework.job.core.scheduler;

import com.richuang.os.framework.common.util.json.JsonUtils;
import com.richuang.os.framework.job.core.handler.JobExecutionParam;
import com.richuang.os.framework.job.core.handler.JobHandlerProcessor;
import com.richuang.os.framework.job.core.util.CronUtils;
import tech.powerjob.client.IPowerJobClient;
import tech.powerjob.common.enums.ExecuteType;
import tech.powerjob.common.enums.ProcessorType;
import tech.powerjob.common.enums.TimeExpressionType;
import tech.powerjob.common.request.http.SaveJobInfoRequest;
import tech.powerjob.common.response.ResultDTO;

/**
 * PowerJob 任务管理器，封装任务元数据的创建、更新、启停和触发。
 *
 * <p>对上层隐藏 PowerJob OpenAPI 请求结构，并将现有 JobHandler 参数统一封装为路由元数据。</p>
 */
public class PowerJobManager {

    private final IPowerJobClient client;

    public PowerJobManager(IPowerJobClient client) {
        this.client = client;
    }

    public Long addJob(Long jobId, String jobName, String handlerName, String handlerParam,
                       String cronExpression, Integer retryCount, Integer retryInterval, boolean enabled) {
        SaveJobInfoRequest request = buildRequest(null, jobId, jobName, handlerName, handlerParam,
                cronExpression, retryCount, retryInterval, enabled);
        return requireSuccess(requireClient().saveJob(request), "创建任务");
    }

    public void updateJob(Long powerJobId, Long jobId, String jobName, String handlerName, String handlerParam,
                          String cronExpression, Integer retryCount, Integer retryInterval, boolean enabled) {
        requirePowerJobId(powerJobId);
        SaveJobInfoRequest request = buildRequest(powerJobId, jobId, jobName, handlerName, handlerParam,
                cronExpression, retryCount, retryInterval, enabled);
        requireSuccess(requireClient().saveJob(request), "更新任务");
    }

    public void deleteJob(Long powerJobId) {
        if (powerJobId == null) {
            return;
        }
        requireSuccess(requireClient().deleteJob(powerJobId), "删除任务");
    }

    public void pauseJob(Long powerJobId) {
        requirePowerJobId(powerJobId);
        requireSuccess(requireClient().disableJob(powerJobId), "暂停任务");
    }

    public void resumeJob(Long powerJobId) {
        requirePowerJobId(powerJobId);
        requireSuccess(requireClient().enableJob(powerJobId), "启用任务");
    }

    public Long triggerJob(Long powerJobId) {
        requirePowerJobId(powerJobId);
        return requireSuccess(requireClient().runJob(powerJobId, null, 0), "触发任务");
    }

    private SaveJobInfoRequest buildRequest(Long powerJobId, Long jobId, String jobName, String handlerName,
                                            String handlerParam, String cronExpression, Integer retryCount,
                                            Integer retryInterval, boolean enabled) {
        JobExecutionParam executionParam = new JobExecutionParam(jobId, handlerName, handlerParam, retryInterval);
        SaveJobInfoRequest request = new SaveJobInfoRequest();
        request.setId(powerJobId);
        request.setJobName(jobName);
        request.setJobDescription("由 os 平台 infra_job 管理");
        request.setJobParams(JsonUtils.toJsonString(executionParam));
        request.setTimeExpressionType(TimeExpressionType.CRON);
        request.setTimeExpression(CronUtils.normalize(cronExpression));
        request.setExecuteType(ExecuteType.STANDALONE);
        request.setProcessorType(ProcessorType.BUILT_IN);
        request.setProcessorInfo(JobHandlerProcessor.PROCESSOR_BEAN_NAME);
        request.setMaxInstanceNum(1);
        request.setConcurrency(1);
        request.setInstanceRetryNum(0);
        request.setTaskRetryNum(retryCount == null ? 0 : retryCount);
        request.setEnable(enabled);
        return request;
    }

    private IPowerJobClient requireClient() {
        if (client == null) {
            throw new PowerJobOperationException("PowerJob 已禁用，请配置 powerjob.client.enabled=true");
        }
        return client;
    }

    private void requirePowerJobId(Long powerJobId) {
        if (powerJobId == null) {
            throw new PowerJobOperationException("任务尚未同步到 PowerJob，请先执行任务同步");
        }
    }

    private <T> T requireSuccess(ResultDTO<T> result, String operation) {
        if (result == null || !result.isSuccess()) {
            String message = result == null ? "PowerJob Server 未返回结果" : result.getMessage();
            throw new PowerJobOperationException(operation + "失败: " + message);
        }
        return result.getData();
    }
}
