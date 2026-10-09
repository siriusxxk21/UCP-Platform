package com.lingan.ucp.module.bpm.api.task;

import com.lingan.ucp.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;

import jakarta.validation.Valid;

/** 提供给业务模块的流程实例操作接口。 */
public interface BpmProcessInstanceApi {

    String createProcessInstance(Long userId, @Valid BpmProcessInstanceCreateReqDTO reqDTO);

    /** 沿用底座的发起人和模型撤销资格校验。 */
    default void cancelByStarter(long actor, String instanceId, String reason) {
        throw new UnsupportedOperationException("流程撤回接口尚未实现");
    }
}
