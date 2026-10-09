package com.lingan.ucp.framework.job.core.service;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 定时任务日志 Framework Service，为 PowerJob Processor 提供与业务模块解耦的日志写入能力。
 */
public interface JobLogFrameworkService {

    Long createJobLog(@NotNull(message = "任务编号不能为空") Long jobId,
                      @NotNull(message = "开始时间不能为空") LocalDateTime beginTime,
                      @NotEmpty(message = "Job 处理器的名字不能为空") String jobHandlerName,
                      String jobHandlerParam,
                      @NotNull(message = "第几次执行不能为空") Integer executeIndex);

    void updateJobLogResultAsync(@NotNull(message = "日志编号不能为空") Long logId,
                                 @NotNull(message = "结束时间不能为空") LocalDateTime endTime,
                                 @NotNull(message = "运行时长不能为空") Integer duration,
                                 boolean success, String result);
}
