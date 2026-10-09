package com.lingan.ucp.module.bpm.api.task;

import com.lingan.ucp.module.bpm.api.task.dto.BpmBusinessTaskCompleteReqDTO;
import com.lingan.ucp.module.bpm.api.task.dto.BpmBusinessTaskDTO;
import com.lingan.ucp.module.bpm.service.task.BpmBusinessTaskService;
import com.lingan.ucp.module.bpm.service.task.BpmTaskService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * 流程任务 Api 实现类
 *
 * @author jason
 */
@Service
@Validated
public class BpmProcessTaskApiImpl implements BpmProcessTaskApi {

    @Resource private BpmTaskService bpmTaskService;

    @Resource private BpmBusinessTaskService businessTaskService;

    @Resource private com.lingan.ucp.module.bpm.service.task.BpmMaterialContextService materials;

    @Override
    public com.lingan.ucp.module.bpm.api.task.dto.BpmMaterialReviewContextDTO
            materialReviewContext(long actor, String processInstanceId, String taskId) {
        return materials.context(actor, processInstanceId, taskId);
    }

    @Override
    public BpmBusinessTaskDTO getAssignedTask(long actor, String taskId) {
        return businessTaskService.getAssignedTask(actor, taskId);
    }

    @Override
    public void completeBusinessTask(long actor, BpmBusinessTaskCompleteReqDTO command) {
        businessTaskService.complete(actor, command);
    }

    @Override
    public void triggerTask(String processInstanceId, String taskDefineKey) {
        bpmTaskService.triggerTask(processInstanceId, taskDefineKey);
    }
}
