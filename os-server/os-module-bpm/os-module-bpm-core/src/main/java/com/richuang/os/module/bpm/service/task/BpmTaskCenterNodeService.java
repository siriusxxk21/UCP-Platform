package com.richuang.os.module.bpm.service.task;

import com.richuang.os.module.bpm.api.task.BpmTaskCenterNodeApi.State;
import com.richuang.os.module.bpm.api.task.dto.BpmTaskCenterNodeExecutionDTO;

/** 任务节点的引擎等待点和流程查阅资格。 */
public interface BpmTaskCenterNodeService {
    State inspect(BpmTaskCenterNodeExecutionDTO execution);

    boolean advance(BpmTaskCenterNodeExecutionDTO execution);

    boolean canViewProcess(long actor, String processInstanceId);
}
