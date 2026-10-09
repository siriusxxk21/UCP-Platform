package com.lingan.ucp.module.bpm.api.task;

import com.lingan.ucp.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import com.lingan.ucp.module.bpm.service.task.BpmProcessInstanceService;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * Flowable 流程实例 Api 实现类
 *
 * @author 芋道源码
 * @author jason
 */
@Service
@Validated
public class BpmProcessInstanceApiImpl implements BpmProcessInstanceApi {

    @Override
    public void cancelByStarter(long actor, String instanceId, String reason) {
        var request =
                new com.lingan.ucp.module.bpm.controller.admin.task.vo.instance
                        .BpmProcessInstanceCancelReqVO();
        request.setId(instanceId);
        request.setReason(reason);
        processInstanceService.cancelProcessInstanceByStartUser(actor, request);
    }

    @Resource private BpmProcessInstanceService processInstanceService;

    @Override
    public String createProcessInstance(Long userId, @Valid BpmProcessInstanceCreateReqDTO reqDTO) {
        return processInstanceService.createProcessInstance(userId, reqDTO);
    }
}
