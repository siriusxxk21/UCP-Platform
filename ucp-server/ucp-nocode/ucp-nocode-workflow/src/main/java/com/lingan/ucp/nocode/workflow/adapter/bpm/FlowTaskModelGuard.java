package com.lingan.ucp.nocode.workflow.adapter.bpm;

import com.lingan.ucp.module.bpm.api.task.BpmBusinessModelGuard;
import com.lingan.ucp.nocode.api.workflow.FlowTasks;
import com.lingan.ucp.nocode.runtime.service.work.WorkFormService;
import com.lingan.ucp.nocode.workflow.service.task.FlowTaskBindingService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

/** 模型管理员仍需拥有绑定表单的业务权限；运行时按实际办理人再次校验。 */
@Component
public class FlowTaskModelGuard implements BpmBusinessModelGuard {
    @Resource private FlowTaskBindingService bindings;
    @Resource private WorkFormService forms;
    @Resource private com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper designLocks;

    @Override
    public String handler() {
        return FlowTasks.HANDLER;
    }

    @Override
    public void validate(String nodeId, String configuration, long actor) {
        // 部署/重新启用与应用删除共用同一事务级设计锁，避免校验后新增悬空绑定。
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) throw new IllegalStateException("流程业务绑定校验必须处于模型写事务中");
        designLocks.lockTableName("nocode-design-write");
        var config = bindings.configuration(handler(), configuration);
        forms.validateCreateResource(config.resource(), config.objectId(), actor);
    }
}
