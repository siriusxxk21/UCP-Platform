package com.lingan.ucp.module.bpm.framework.flowable.core.listener;

import com.lingan.ucp.module.bpm.service.task.BpmBusinessTaskService;

import jakarta.annotation.Resource;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.engine.delegate.event.AbstractFlowableEngineEventListener;
import org.flowable.task.api.Task;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 在任务删除及后续节点推进前校验；原审批、批量及直接引擎完成调用都经过此入口。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BpmTaskCompletionGuardListener extends AbstractFlowableEngineEventListener {
    @Resource @Lazy private BpmBusinessTaskService businessTaskService;

    public BpmTaskCompletionGuardListener() {
        super(Set.of(FlowableEngineEventType.TASK_COMPLETED));
    }

    @Override
    protected void taskCompleted(FlowableEngineEntityEvent event) {
        businessTaskService.validateCompletion((Task) event.getEntity());
    }

    @Override
    public boolean isFailOnException() {
        return true;
    }
}
