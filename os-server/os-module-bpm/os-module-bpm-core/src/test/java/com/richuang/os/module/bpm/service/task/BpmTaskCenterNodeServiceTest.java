package com.richuang.os.module.bpm.service.task;

import static com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnModelConstants.*;
import static com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_STATUS;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.richuang.os.framework.tenant.core.context.TenantContextHolder;
import com.richuang.os.module.bpm.api.task.BpmTaskCenterNodeApi.State;
import com.richuang.os.module.bpm.api.task.dto.BpmTaskCenterNodeExecutionDTO;
import com.richuang.os.module.bpm.dal.mysql.task.BpmProcessInstanceCopyMapper;
import com.richuang.os.module.bpm.enums.task.BpmProcessInstanceStatusEnum;
import com.richuang.os.module.bpm.framework.flowable.core.util.BpmnModelUtils;
import com.richuang.os.module.bpm.service.definition.BpmProcessDefinitionService;
import com.richuang.os.module.system.api.permission.PermissionApi;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.ReceiveTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 精确等待点协议：旧绑定、跨租户、暂停、取消和重复完成均不得推进。 */
class BpmTaskCenterNodeServiceTest {
    private BpmTaskCenterNodeServiceImpl service;
    private RuntimeService runtime;
    private Execution execution;
    private ProcessInstance process;
    private final BpmTaskCenterNodeExecutionDTO command =
            new BpmTaskCenterNodeExecutionDTO("execution", "process", "taskNode", "binding");

    @BeforeEach
    void setup() {
        TenantContextHolder.setTenantId(1L);
        service = new BpmTaskCenterNodeServiceImpl();
        runtime = mock(RuntimeService.class, RETURNS_DEEP_STUBS);
        RepositoryService repository = mock(RepositoryService.class);
        ReflectionTestUtils.setField(service, "runtimeService", runtime);
        ReflectionTestUtils.setField(service, "repositoryService", repository);
        execution = mock(Execution.class);
        process = mock(ProcessInstance.class);
        when(runtime.createExecutionQuery().executionId("execution").singleResult())
                .thenReturn(execution);
        when(runtime.createProcessInstanceQuery().processInstanceId("process").singleResult())
                .thenReturn(process);
        when(execution.getTenantId()).thenReturn("1");
        when(execution.getProcessInstanceId()).thenReturn("process");
        when(execution.getActivityId()).thenReturn("taskNode");
        when(process.getTenantId()).thenReturn("1");
        when(process.getProcessDefinitionId()).thenReturn("definition");
        when(runtime.getVariableLocal("execution", TASK_CENTER_BINDING_ID)).thenReturn("binding");
        when(runtime.getVariable("process", PROCESS_INSTANCE_VARIABLE_STATUS))
                .thenReturn(BpmProcessInstanceStatusEnum.RUNNING.getStatus());
        BpmnModel model = new BpmnModel();
        Process flow = new Process();
        flow.setId("TaskFlow");
        model.addProcess(flow);
        ReceiveTask node = new ReceiveTask();
        node.setId("taskNode");
        BpmnModelUtils.addExtensionElement(node, NODE_TYPE, 16);
        flow.addFlowElement(node);
        when(repository.getBpmnModel("definition")).thenReturn(model);
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
    }

    @Test
    void advancesOnlyExactWaitingBinding() {
        assertEquals(State.WAITING, service.inspect(command));
        assertTrue(service.advance(command));
        verify(runtime).trigger("execution");
        when(runtime.createExecutionQuery().executionId("execution").singleResult())
                .thenReturn(null);
        assertFalse(service.advance(command));
        verify(runtime, times(1)).trigger("execution");
    }

    @Test
    void rejectsOldBindingWrongNodeAndOtherTenant() {
        assertFalse(
                service.advance(
                        new BpmTaskCenterNodeExecutionDTO(
                                "execution", "process", "taskNode", "old")));
        assertFalse(
                service.advance(
                        new BpmTaskCenterNodeExecutionDTO(
                                "execution", "process", "otherNode", "binding")));
        TenantContextHolder.setTenantId(2L);
        assertFalse(service.advance(command));
        verify(runtime, never()).trigger(anyString());
    }

    @Test
    void pausedAndEndedProcessesNeverAdvance() {
        when(process.isSuspended()).thenReturn(true);
        assertEquals(State.SUSPENDED, service.inspect(command));
        assertFalse(service.advance(command));
        when(process.isSuspended()).thenReturn(false);
        when(process.isEnded()).thenReturn(true);
        assertEquals(State.INACTIVE, service.inspect(command));
        assertFalse(service.advance(command));
        verify(runtime, never()).trigger(anyString());
    }

    @Test
    void abnormalTerminalStatusNeverAdvances() {
        when(runtime.getVariable("process", PROCESS_INSTANCE_VARIABLE_STATUS))
                .thenReturn(BpmProcessInstanceStatusEnum.CANCEL.getStatus());
        assertEquals(State.INACTIVE, service.inspect(command));
        assertFalse(service.advance(command));
        verify(runtime, never()).trigger(anyString());
    }

    @Test
    void defaultTenantExecutionMatchesZeroAndMissingContextButNotPositiveTenant() {
        when(execution.getTenantId()).thenReturn("");
        when(process.getTenantId()).thenReturn(null);
        TenantContextHolder.setTenantId(0L);
        assertEquals(State.WAITING, service.inspect(command));
        assertTrue(service.advance(command));

        TenantContextHolder.clear();
        assertEquals(State.WAITING, service.inspect(command));
        TenantContextHolder.setTenantId(1L);
        assertEquals(State.INACTIVE, service.inspect(command));
        assertFalse(service.advance(command));
        verify(runtime, times(1)).trigger("execution");
    }

    @Test
    void defaultTenantCannotInspectPositiveTenantExecution() {
        TenantContextHolder.setTenantId(0L);
        assertEquals(State.INACTIVE, service.inspect(command));
        assertFalse(service.advance(command));
        TenantContextHolder.clear();
        assertEquals(State.INACTIVE, service.inspect(command));
        verify(runtime, never()).trigger(anyString());
    }

    @Test
    void taskVisibilityDoesNotGrantAccessToUnrelatedFlow() {
        HistoryService history = permissionFixture();
        PermissionApi permissions = mock(PermissionApi.class);
        ReflectionTestUtils.setField(service, "permissionApi", permissions);
        when(permissions.hasAnyPermissions(9L, "bpm:process-instance:query")).thenReturn(true);

        assertFalse(service.canViewProcess(9L, "process"));
        verify(history).createHistoricTaskInstanceQuery();
    }

    @Test
    void starterAndFlowManagerRetainTheirExistingQueryRights() {
        permissionFixture();
        PermissionApi permissions = mock(PermissionApi.class);
        ReflectionTestUtils.setField(service, "permissionApi", permissions);
        when(permissions.hasAnyPermissions(7L, "bpm:process-instance:query")).thenReturn(true);
        when(permissions.hasAnyPermissions(9L, "bpm:process-instance:manager-query"))
                .thenReturn(true);

        assertTrue(service.canViewProcess(7L, "process"));
        assertTrue(service.canViewProcess(9L, "process"));
        assertFalse(service.canViewProcess(8L, "process"));
    }

    @Test
    void managerQueryNeverCrossesTenantBoundary() {
        permissionFixture();
        PermissionApi permissions = mock(PermissionApi.class);
        ReflectionTestUtils.setField(service, "permissionApi", permissions);
        TenantContextHolder.setTenantId(2L);

        assertFalse(service.canViewProcess(9L, "process"));
        verifyNoInteractions(permissions);
    }

    @Test
    void defaultTenantProcessQueryUsesTheSameNamespaceNormalization() {
        permissionFixture("");
        PermissionApi permissions = mock(PermissionApi.class);
        ReflectionTestUtils.setField(service, "permissionApi", permissions);
        when(permissions.hasAnyPermissions(7L, "bpm:process-instance:query")).thenReturn(true);

        TenantContextHolder.setTenantId(0L);
        assertTrue(service.canViewProcess(7L, "process"));
        TenantContextHolder.clear();
        assertTrue(service.canViewProcess(7L, "process"));
        TenantContextHolder.setTenantId(1L);
        assertFalse(service.canViewProcess(7L, "process"));
    }

    private HistoryService permissionFixture() {
        return permissionFixture("1");
    }

    private HistoryService permissionFixture(String tenantId) {
        HistoryService history = mock(HistoryService.class, RETURNS_DEEP_STUBS);
        // 查询接口继承泛型链式方法，显式自返回比 deep-stub 的泛型推断更可靠。
        HistoricTaskInstanceQuery taskQuery = mock(HistoricTaskInstanceQuery.class);
        when(history.createHistoricTaskInstanceQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId(anyString())).thenReturn(taskQuery);
        when(taskQuery.taskAssignee(anyString())).thenReturn(taskQuery);
        HistoricProcessInstance historic = mock(HistoricProcessInstance.class);
        when(history.createHistoricProcessInstanceQuery()
                        .processInstanceId("process")
                        .singleResult())
                .thenReturn(historic);
        when(historic.getTenantId()).thenReturn(tenantId);
        when(historic.getStartUserId()).thenReturn("7");
        when(historic.getProcessDefinitionId()).thenReturn("definition");
        ReflectionTestUtils.setField(service, "historyService", history);
        ReflectionTestUtils.setField(
                service, "definitions", mock(BpmProcessDefinitionService.class));
        ReflectionTestUtils.setField(service, "copies", mock(BpmProcessInstanceCopyMapper.class));
        return history;
    }
}
