package com.richuang.os.module.bpm.service.task;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.richuang.os.module.bpm.enums.task.BpmTaskStatusEnum;
import com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnVariableConstants;
import com.richuang.os.module.bpm.service.definition.BpmModelService;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.Process;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ChangeActivityStateBuilder;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

/** 取消流程必须结束非审批等待点，同时保留审批取消标记及历史。 */
class BpmTaskCancellationTest {
    private BpmTaskServiceImpl service;
    private RuntimeService runtime;
    private BpmModelService models;
    private HistoryService history;
    private ChangeActivityStateBuilder changes;
    private ProcessInstance process;

    @BeforeEach
    void setup() {
        service = spy(new BpmTaskServiceImpl());
        runtime = mock(RuntimeService.class, RETURNS_DEEP_STUBS);
        models = mock(BpmModelService.class);
        history = mock(HistoryService.class);
        changes = mock(ChangeActivityStateBuilder.class, RETURNS_SELF);
        process = mock(ProcessInstance.class);
        ReflectionTestUtils.setField(service, "runtimeService", runtime);
        ReflectionTestUtils.setField(service, "modelService", models);
        ReflectionTestUtils.setField(service, "historyService", history);
        when(runtime.createProcessInstanceQuery().processInstanceId("process").singleResult())
                .thenReturn(process);
        when(process.getProcessDefinitionId()).thenReturn("definition");
        when(runtime.createChangeActivityStateBuilder()).thenReturn(changes);
        when(runtime.createExecutionQuery().processInstanceId("process").list())
                .thenReturn(List.of());
        doReturn(List.of())
                .when(service)
                .getRunningTaskListByProcessInstanceId("process", null, null);

        BpmnModel model = new BpmnModel();
        Process main = new Process();
        main.setId("flow");
        EndEvent end = new EndEvent();
        end.setId("end");
        main.addFlowElement(end);
        model.addProcess(main);
        when(models.getBpmnModelByDefinitionId("definition")).thenReturn(model);
    }

    @Test
    void receiveTaskWithoutUserTaskStillMovesToEndWithoutTriggeringItsSuccessor() {
        when(runtime.getActiveActivityIds("process")).thenReturn(List.of("taskCenterWait"));

        service.moveTaskToEnd("process", "撤销流程");

        verify(changes).processInstanceId("process");
        verify(changes).moveActivityIdsToSingleActivityId(List.of("taskCenterWait"), "end");
        verify(changes).changeState();
        verify(runtime, never()).trigger(anyString());
        verify(runtime, never()).deleteProcessInstance(anyString(), anyString());
        verifyNoInteractions(history);
    }

    @Test
    void parallelWaitAndMultiInstanceApprovalAreMergedOncePerActivity() {
        Task pending = task("pending", BpmTaskStatusEnum.RUNNING.getStatus());
        Task finished = task("finished", BpmTaskStatusEnum.APPROVE.getStatus());
        doReturn(List.of(pending, finished))
                .when(service)
                .getRunningTaskListByProcessInstanceId("process", null, null);
        doNothing().when(service).processTaskCanceled("pending");
        when(runtime.getActiveActivityIds("process"))
                .thenReturn(List.of("taskCenterWait", "approval", "approval"));

        service.moveTaskToEnd("process", "撤销流程");

        InOrder order = inOrder(service, changes);
        order.verify(service).processTaskCanceled("pending");
        order.verify(changes)
                .moveActivityIdsToSingleActivityId(List.of("taskCenterWait", "approval"), "end");
        order.verify(changes).changeState();
        verify(service, never()).processTaskCanceled("finished");
        verifyNoInteractions(history);
    }

    @Test
    void residualExecutionIsDeletedUsingTheExistingHistoryPreservingFallback() {
        when(runtime.getActiveActivityIds("process")).thenReturn(List.of("subprocessWait"));
        when(runtime.createExecutionQuery().processInstanceId("process").list())
                .thenReturn(List.of(mock(Execution.class)));

        service.moveTaskToEnd("process", "管理员撤销");

        InOrder order = inOrder(changes, runtime);
        order.verify(changes).changeState();
        order.verify(runtime).deleteProcessInstance("process", "管理员撤销");
        verifyNoInteractions(history);
    }

    @Test
    void remainingScopeWithoutActiveActivityIsAlsoTerminated() {
        when(runtime.getActiveActivityIds("process")).thenReturn(List.of());
        when(runtime.createExecutionQuery().processInstanceId("process").list())
                .thenReturn(List.of(mock(Execution.class)));

        service.moveTaskToEnd("process", "撤销流程");

        verify(runtime).deleteProcessInstance("process", "撤销流程");
        verifyNoInteractions(models, changes, history);
    }

    @Test
    void alreadyEndedProcessDoesNotRecreateOrDeleteAnything() {
        when(runtime.createProcessInstanceQuery().processInstanceId("process").singleResult())
                .thenReturn(null);

        service.moveTaskToEnd("process", "撤销流程");

        verify(service, never()).getRunningTaskListByProcessInstanceId(any(), any(), any());
        verify(runtime, never()).getActiveActivityIds(anyString());
        verify(runtime, never()).deleteProcessInstance(anyString(), anyString());
        verifyNoInteractions(models, changes, history);
    }

    @Test
    void failedStateChangePropagatesToTheCallingCancellationTransaction() {
        when(runtime.getActiveActivityIds("process")).thenReturn(List.of("taskCenterWait"));
        doThrow(new IllegalStateException("并发更新")).when(changes).changeState();

        assertThrows(IllegalStateException.class, () -> service.moveTaskToEnd("process", "撤销流程"));

        verify(runtime, never()).deleteProcessInstance(anyString(), anyString());
        verifyNoInteractions(history);
    }

    private Task task(String id, int status) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(id);
        when(task.getTaskLocalVariables())
                .thenReturn(Map.of(BpmnVariableConstants.TASK_VARIABLE_STATUS, status));
        return task;
    }
}
