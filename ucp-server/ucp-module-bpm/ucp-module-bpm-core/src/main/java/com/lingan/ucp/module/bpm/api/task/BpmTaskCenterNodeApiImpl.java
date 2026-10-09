package com.lingan.ucp.module.bpm.api.task;

import com.lingan.ucp.module.bpm.api.task.dto.BpmTaskCenterNodeExecutionDTO;
import com.lingan.ucp.module.bpm.service.task.BpmTaskCenterNodeService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

/** 任务中心调用的受限引擎适配，不提供任意节点触发能力。 */
@Service
public class BpmTaskCenterNodeApiImpl implements BpmTaskCenterNodeApi {
    @Resource private BpmTaskCenterNodeService service;

    @Override
    public State inspect(BpmTaskCenterNodeExecutionDTO execution) {
        return service.inspect(execution);
    }

    @Override
    public boolean advance(BpmTaskCenterNodeExecutionDTO execution) {
        return service.advance(execution);
    }

    @Override
    public boolean canViewProcess(long actor, String processInstanceId) {
        return service.canViewProcess(actor, processInstanceId);
    }
}
