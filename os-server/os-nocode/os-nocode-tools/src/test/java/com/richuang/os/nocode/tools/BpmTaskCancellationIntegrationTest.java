package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.richuang.os.module.bpm.controller.admin.task.vo.instance.BpmProcessInstanceCancelReqVO;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import com.richuang.os.module.bpm.enums.task.BpmProcessInstanceStatusEnum;
import com.richuang.os.module.bpm.enums.task.BpmTaskStatusEnum;
import com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnVariableConstants;
import com.richuang.os.module.bpm.service.definition.BpmProcessDefinitionService;
import com.richuang.os.module.bpm.service.task.BpmProcessInstanceService;
import com.richuang.os.module.bpm.service.task.BpmProcessInstanceServiceImpl;
import com.richuang.os.module.bpm.service.task.BpmTaskService;

import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 正式撤销入口的真实引擎回归，复用当前开发库装配且仅清理自己的 UUID 部署。 */
class BpmTaskCancellationIntegrationTest {
    private static ProcessEngine engine;
    private static BpmTaskService tasks;
    private final BpmTaskCompletionIntegrationTest fixture = new BpmTaskCompletionIntegrationTest();
    private BpmProcessInstanceService cancellations;

    @BeforeAll
    static void open() {
        BpmTaskCompletionIntegrationTest.open();
        engine = BpmTaskCompletionIntegrationTest.engine;
        tasks = BpmTaskCompletionIntegrationTest.context.getBean(BpmTaskService.class);
    }

    @AfterAll
    static void close() {
        BpmTaskCompletionIntegrationTest.close();
    }

    @BeforeEach
    void configure() {
        BpmProcessDefinitionService definitions =
                BpmTaskCompletionIntegrationTest.context.getBean(BpmProcessDefinitionService.class);
        BpmProcessDefinitionInfoDO definition = new BpmProcessDefinitionInfoDO();
        definition.setAllowCancelRunningProcess(true);
        when(definitions.getProcessDefinitionInfo(anyString())).thenReturn(definition);

        BpmProcessInstanceServiceImpl service = new BpmProcessInstanceServiceImpl();
        ReflectionTestUtils.setField(service, "runtimeService", engine.getRuntimeService());
        ReflectionTestUtils.setField(service, "taskService", tasks);
        ReflectionTestUtils.setField(service, "processDefinitionService", definitions);
        ProxyFactory proxy = new ProxyFactory(service);
        proxy.addAdvice(
                new TransactionInterceptor(
                        BpmTaskCompletionIntegrationTest.tool.getBean(
                                PlatformTransactionManager.class),
                        new AnnotationTransactionAttributeSource()));
        cancellations = (BpmProcessInstanceService) proxy.getProxy();
    }

    @AfterEach
    void clean() {
        Authentication.setAuthenticatedUserId(null);
        fixture.clean();
    }

    @Test
    void parallelReceiveAndApprovalAreCancelledWithoutAdvancingTheJoin() {
        ProcessInstance process = start(parallelBody(""));
        List<Task> pending = pendingTasks(process);
        assertThat(pending).hasSize(1);
        assertThat(engine.getRuntimeService().getActiveActivityIds(process.getId()))
                .contains("waitingData", "waitingApproval");

        cancel(process);

        assertEndedWithoutSuccessor(process, pending);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void multiInstanceApprovalAndReceiveLeaveNoLoopRootOrNewIteration(boolean sequential) {
        String loop =
                "<multiInstanceLoopCharacteristics isSequential=\""
                        + sequential
                        + "\"><loopCardinality>3</loopCardinality></multiInstanceLoopCharacteristics>";
        ProcessInstance process = start(parallelBody(loop));
        List<Task> pending = pendingTasks(process);
        assertThat(pending).hasSize(sequential ? 1 : 3);
        assertThat(engine.getRuntimeService().getActiveActivityIds(process.getId()))
                .contains("waitingData", "waitingApproval");

        cancel(process);

        assertEndedWithoutSuccessor(process, pending);
        assertThat(
                        engine.getHistoryService()
                                .createHistoricTaskInstanceQuery()
                                .processInstanceId(process.getId())
                                .taskDefinitionKey("waitingApproval")
                                .count())
                .isEqualTo(pending.size());
    }

    @Test
    void nestedReceiveWithoutUserTasksEndsTheContainingProcessWithoutInnerOrOuterSuccessors() {
        ProcessInstance process =
                start(
                        """
<startEvent id="start"/>
<sequenceFlow id="s" sourceRef="start" targetRef="nested"/>
<subProcess id="nested">
  <startEvent id="innerStart"/>
  <sequenceFlow id="innerS" sourceRef="innerStart" targetRef="waitingData"/>
  <receiveTask id="waitingData" name="子流程任务中心等待"/>
  <sequenceFlow id="innerF" sourceRef="waitingData" targetRef="innerAfter"/>
  <userTask id="innerAfter" name="不得生成的内部后继审批" flowable:assignee="10001"/>
  <sequenceFlow id="innerE" sourceRef="innerAfter" targetRef="innerEnd"/>
  <endEvent id="innerEnd"/>
</subProcess>
<sequenceFlow id="outerF" sourceRef="nested" targetRef="after"/>
<userTask id="after" name="不得生成的外部后继审批" flowable:assignee="10001"/>
<sequenceFlow id="e" sourceRef="after" targetRef="end"/>
<endEvent id="end"/>
""");
        assertThat(pendingTasks(process)).isEmpty();
        assertThat(engine.getRuntimeService().getActiveActivityIds(process.getId()))
                .contains("waitingData");

        cancel(process);

        assertEndedWithoutSuccessor(process, List.of());
        assertThat(
                        engine.getHistoryService()
                                .createHistoricTaskInstanceQuery()
                                .processInstanceId(process.getId())
                                .taskDefinitionKey("innerAfter")
                                .count())
                .isZero();
    }

    private void cancel(ProcessInstance process) {
        cancellations.cancelProcessInstanceByStartUser(
                10001L,
                new BpmProcessInstanceCancelReqVO().setId(process.getId()).setReason("活动节点取消回归"));
    }

    private void assertEndedWithoutSuccessor(ProcessInstance process, List<Task> pending) {
        String processId = process.getId();
        assertThat(
                        engine.getRuntimeService()
                                .createProcessInstanceQuery()
                                .processInstanceId(processId)
                                .count())
                .isZero();
        assertThat(
                        engine.getRuntimeService()
                                .createExecutionQuery()
                                .processInstanceId(processId)
                                .count())
                .isZero();
        assertThat(pendingTasks(process)).isEmpty();
        HistoricProcessInstance history =
                engine.getHistoryService()
                        .createHistoricProcessInstanceQuery()
                        .processInstanceId(processId)
                        .includeProcessVariables()
                        .singleResult();
        assertThat(history).isNotNull();
        assertThat(history.getEndTime()).isNotNull();
        assertThat(history.getProcessVariables())
                .containsEntry(
                        BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_STATUS,
                        BpmProcessInstanceStatusEnum.CANCEL.getStatus());
        assertThat(
                        engine.getHistoryService()
                                .createHistoricTaskInstanceQuery()
                                .processInstanceId(processId)
                                .taskDefinitionKey("after")
                                .count())
                .isZero();
        for (Task task : pending) {
            HistoricTaskInstance cancelled =
                    engine.getHistoryService()
                            .createHistoricTaskInstanceQuery()
                            .taskId(task.getId())
                            .includeTaskLocalVariables()
                            .singleResult();
            assertThat(cancelled).isNotNull();
            assertThat(cancelled.getEndTime()).isNotNull();
            assertThat(cancelled.getTaskLocalVariables())
                    .containsEntry(
                            BpmnVariableConstants.TASK_VARIABLE_STATUS,
                            BpmTaskStatusEnum.CANCEL.getStatus());
        }
    }

    private List<Task> pendingTasks(ProcessInstance process) {
        return engine.getTaskService().createTaskQuery().processInstanceId(process.getId()).list();
    }

    private String parallelBody(String loop) {
        return """
               <startEvent id="start"/>
               <sequenceFlow id="s" sourceRef="start" targetRef="fork"/>
               <parallelGateway id="fork"/>
               <sequenceFlow id="a" sourceRef="fork" targetRef="waitingData"/>
               <sequenceFlow id="b" sourceRef="fork" targetRef="waitingApproval"/>
               <receiveTask id="waitingData" name="任务中心等待"/>
               <userTask id="waitingApproval" name="同时审批" flowable:assignee="10001">%s</userTask>
               <sequenceFlow id="c" sourceRef="waitingData" targetRef="join"/>
               <sequenceFlow id="d" sourceRef="waitingApproval" targetRef="join"/>
               <parallelGateway id="join"/>
               <sequenceFlow id="f" sourceRef="join" targetRef="after"/>
               <userTask id="after" name="不得生成的后继审批" flowable:assignee="10001"/>
               <sequenceFlow id="e" sourceRef="after" targetRef="end"/>
               <endEvent id="end"/>
               """
                .formatted(loop);
    }

    private ProcessInstance start(String body) {
        String key = "cancel_activity_" + UUID.randomUUID().toString().replace("-", "");
        String xml =
                """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="cancel-activity">
                  <process id="%s" isExecutable="true">%s</process>
                </definitions>
                """
                        .formatted(key, body);
        Deployment deployment =
                engine.getRepositoryService()
                        .createDeployment()
                        .addString(key + ".bpmn20.xml", xml)
                        .deploy();
        fixture.deployments.add(deployment.getId());
        Authentication.setAuthenticatedUserId("10001");
        try {
            return engine.getRuntimeService()
                    .startProcessInstanceByKey(
                            key,
                            Map.of(
                                    BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_STATUS,
                                    BpmProcessInstanceStatusEnum.RUNNING.getStatus()));
        } finally {
            Authentication.setAuthenticatedUserId(null);
        }
    }
}
