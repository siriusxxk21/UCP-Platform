package com.richuang.os.module.bpm.framework.flowable.core.listener;

import static com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnModelConstants.*;
import static com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_START_USER_ID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.richuang.os.module.bpm.api.task.BpmTaskCenterNodeHandler;
import com.richuang.os.module.bpm.api.task.dto.BpmTaskCenterNodeArrivalDTO;
import com.richuang.os.module.bpm.framework.flowable.core.util.BpmnModelUtils;

import org.flowable.bpmn.model.ReceiveTask;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** 到达协议只提取人员变量并保存本轮绑定，不传业务数据、不推进引擎。 */
class BpmTaskCenterNodeListenerTest {
    @Test
    void registersOnlyRequestedPeopleAndSealsExecutionToken() {
        BpmTaskCenterNodeListener listener = new BpmTaskCenterNodeListener();
        BpmTaskCenterNodeHandler handler = mock(BpmTaskCenterNodeHandler.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BpmTaskCenterNodeHandler> provider = mock(ObjectProvider.class);
        when(provider.stream()).thenAnswer(ignored -> Stream.of(handler));
        ReflectionTestUtils.setField(listener, "handlers", provider);
        DelegateExecution execution = mock(DelegateExecution.class);
        ReceiveTask node = new ReceiveTask();
        node.setId("work");
        BpmnModelUtils.addExtensionElement(node, TASK_CENTER_CONFIG, "{\"version\":1}");
        when(execution.getCurrentFlowElement()).thenReturn(node);
        when(execution.getTenantId()).thenReturn("1");
        when(execution.getId()).thenReturn("execution");
        when(execution.getProcessInstanceId()).thenReturn("process");
        when(execution.getProcessDefinitionId()).thenReturn("definition");
        when(execution.getCurrentActivityId()).thenReturn("work");
        when(execution.getCurrentActivityName()).thenReturn("装修任务");
        when(execution.getVariable(PROCESS_INSTANCE_VARIABLE_START_USER_ID)).thenReturn(7L);
        when(execution.getVariable("worker")).thenReturn(8L);
        when(handler.neededFields(anyString())).thenReturn(Set.of("worker"));
        when(handler.arrive(any())).thenReturn("binding");

        listener.notify(execution);

        ArgumentCaptor<BpmTaskCenterNodeArrivalDTO> capture =
                ArgumentCaptor.forClass(BpmTaskCenterNodeArrivalDTO.class);
        verify(handler).arrive(capture.capture());
        assertEquals(Map.of("worker", 8L), capture.getValue().variables());
        assertEquals(7L, capture.getValue().initiatorId());
        assertEquals(1L, capture.getValue().tenantId());
        verify(execution).setVariableLocal(TASK_CENTER_BINDING_ID, "binding");
        verify(execution, never()).getVariables();
    }
}
