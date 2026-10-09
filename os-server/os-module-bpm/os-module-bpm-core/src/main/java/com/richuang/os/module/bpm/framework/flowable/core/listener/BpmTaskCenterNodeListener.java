package com.richuang.os.module.bpm.framework.flowable.core.listener;

import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.richuang.os.module.bpm.enums.ErrorCodeConstants.*;
import static com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnModelConstants.*;
import static com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_START_USER_ID;

import com.richuang.os.module.bpm.api.task.BpmTaskCenterNodeHandler;
import com.richuang.os.module.bpm.api.task.dto.BpmTaskCenterNodeArrivalDTO;
import com.richuang.os.module.bpm.framework.flowable.core.util.BpmnModelUtils;
import com.richuang.os.module.bpm.framework.flowable.core.util.FlowableUtils;

import jakarta.annotation.Resource;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 到达时只登记可靠待办，任务创建和完成推进交由桥接服务重试，不阻塞前序审批。 */
@Component(BpmTaskCenterNodeListener.BEAN_NAME)
public class BpmTaskCenterNodeListener implements ExecutionListener {
    public static final String BEAN_NAME = "bpmTaskCenterNodeListener";

    @Resource private ObjectProvider<BpmTaskCenterNodeHandler> handlers;

    @Override
    public void notify(DelegateExecution execution) {
        FlowableUtils.execute(execution.getTenantId(), () -> register(execution));
    }

    private void register(DelegateExecution execution) {
        List<BpmTaskCenterNodeHandler> available = handlers.stream().toList();
        if (available.size() != 1) throw exception(TASK_CENTER_NODE_HANDLER_UNAVAILABLE);
        BpmTaskCenterNodeHandler handler = available.getFirst();
        String configuration =
                BpmnModelUtils.parseExtensionElement(
                        execution.getCurrentFlowElement(), TASK_CENTER_CONFIG);
        if (configuration == null || configuration.isBlank()) {
            throw exception(
                    TASK_CENTER_NODE_INVALID, execution.getCurrentActivityName(), "缺少已发布任务配置");
        }
        Map<String, Object> people = new LinkedHashMap<>();
        for (String field : handler.neededFields(configuration)) {
            people.put(field, execution.getVariable(field));
        }
        Object initiator = execution.getVariable(PROCESS_INSTANCE_VARIABLE_START_USER_ID);
        String bindingId =
                handler.arrive(
                        new BpmTaskCenterNodeArrivalDTO(
                                execution.getId(),
                                execution.getProcessInstanceId(),
                                execution.getProcessDefinitionId(),
                                execution.getCurrentActivityId(),
                                execution.getCurrentActivityName(),
                                initiator == null ? null : Long.valueOf(initiator.toString()),
                                execution.getTenantId() == null || execution.getTenantId().isBlank()
                                        ? null
                                        : Long.valueOf(execution.getTenantId()),
                                configuration,
                                people));
        if (bindingId == null || bindingId.isBlank()) {
            throw exception(
                    TASK_CENTER_NODE_INVALID, execution.getCurrentActivityName(), "未登记任务节点运行记录");
        }
        execution.setVariableLocal(TASK_CENTER_BINDING_ID, bindingId);
    }
}
