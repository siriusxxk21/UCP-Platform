package com.richuang.os.module.bpm.service.task;

import com.richuang.os.module.bpm.api.task.dto.BpmBusinessTaskCompleteReqDTO;
import com.richuang.os.module.bpm.api.task.dto.BpmBusinessTaskDTO;

import org.flowable.task.api.Task;

/** 稳定任务 API 与引擎级完成校验共用的服务入口。 */
public interface BpmBusinessTaskService {
    BpmBusinessTaskDTO getAssignedTask(long actor, String taskId);

    void complete(long actor, BpmBusinessTaskCompleteReqDTO command);

    void validateCompletion(Task task);
}
