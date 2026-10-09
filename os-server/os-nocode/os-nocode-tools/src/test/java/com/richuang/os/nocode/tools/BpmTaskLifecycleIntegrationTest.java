package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.richuang.os.module.bpm.controller.admin.task.vo.instance.BpmProcessInstanceCancelReqVO;
import com.richuang.os.module.bpm.controller.admin.task.vo.task.*;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import com.richuang.os.module.bpm.enums.task.BpmProcessInstanceStatusEnum;
import com.richuang.os.module.bpm.enums.task.BpmTaskStatusEnum;
import com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnVariableConstants;
import com.richuang.os.module.bpm.service.definition.BpmProcessDefinitionService;
import com.richuang.os.module.bpm.service.task.*;

import org.flowable.engine.ProcessEngine;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.*;

/** 当前开发库的生命周期回归：复用独立引擎配置，UUID 夹具仅清理自身部署。 */
class BpmTaskLifecycleIntegrationTest {
    static ProcessEngine engine;
    static BpmTaskService tasks;
    BpmTaskCompletionIntegrationTest fixture = new BpmTaskCompletionIntegrationTest();
    BpmProcessDefinitionInfoDO definition;

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
        definition = new BpmProcessDefinitionInfoDO();
        definition.setAllowWithdrawTask(true);
        definition.setAllowCancelRunningProcess(true);
        when(BpmTaskCompletionIntegrationTest.context
                        .getBean(BpmProcessDefinitionService.class)
                        .getProcessDefinitionInfo(anyString()))
                .thenReturn(definition);
    }

    @AfterEach
    void clean() {
        engine.getProcessEngineConfiguration().getClock().reset();
        fixture.clean();
    }

    @Test
    void endedProcessCannotBeWithdrawnAndDoesNotRecreateRuntime() {
        Task first = start(1, false);
        approve(first);
        assertThatThrownBy(() -> tasks.withdrawTask(10001L, first.getId()))
                .hasMessageContaining("未运行");
        assertThat(withdrawable(first)).isFalse();
        assertThat(
                        engine.getRuntimeService()
                                .createProcessInstanceQuery()
                                .processInstanceId(first.getProcessInstanceId())
                                .count())
                .isZero();
    }

    @Test
    void cancelledProcessCannotWithdrawEarlierApprovedTask() {
        Task first = start(2, false);
        approve(first);
        cancelService(tasks).cancelProcessInstanceByStartUser(10001L, cancel(first));
        assertThatThrownBy(() -> tasks.withdrawTask(10001L, first.getId()))
                .hasMessageContaining("未运行");
        assertThat(withdrawable(first)).isFalse();
    }

    @Test
    void suspendedProcessRejectsWithdrawalAndEveryCommonTaskOperationBeforeWriting() {
        Task first = start(2, false);
        approve(first);
        Task next = current(first);
        engine.getRuntimeService().suspendProcessInstanceById(first.getProcessInstanceId());
        assertThat(withdrawable(first)).isFalse();
        assertThatThrownBy(() -> tasks.withdrawTask(10001L, first.getId()))
                .hasMessageContaining("未运行");
        assertThatThrownBy(
                        () ->
                                tasks.approveTask(
                                        10001L, new BpmTaskApproveReqVO().setId(next.getId())))
                .hasMessageContaining("挂起");
        assertThatThrownBy(
                        () ->
                                tasks.rejectTask(
                                        10001L, new BpmTaskRejectReqVO().setId(next.getId())))
                .hasMessageContaining("挂起");
        assertThatThrownBy(
                        () ->
                                tasks.returnTask(
                                        10001L,
                                        new BpmTaskReturnReqVO()
                                                .setId(next.getId())
                                                .setTargetTaskDefinitionKey("work1")))
                .hasMessageContaining("挂起");
        assertThatThrownBy(
                        () ->
                                tasks.transferTask(
                                        10001L,
                                        new BpmTaskTransferReqVO()
                                                .setId(next.getId())
                                                .setAssigneeUserId(20002L)))
                .hasMessageContaining("挂起");
        assertThatThrownBy(
                        () ->
                                tasks.delegateTask(
                                        10001L,
                                        new BpmTaskDelegateReqVO()
                                                .setId(next.getId())
                                                .setDelegateUserId(20002L)))
                .hasMessageContaining("挂起");
        assertThat(engine.getTaskService().getTaskComments(next.getId())).isEmpty();
        assertThat(current(first).getId()).isEqualTo(next.getId());
    }

    @Test
    void unassignedTaskCannotBeClaimedImplicitlyByLoggedInCaller() {
        Task first = start(1, false);
        engine.getTaskService().setAssignee(first.getId(), null);
        assertThatThrownBy(() -> tasks.validateTask(10001L, first.getId()))
                .hasMessageContaining("不是你");
        // 系统自动审批使用 null actor 的既有协议继续保留。
        assertThat(tasks.validateTask(null, first.getId())).isNotNull();
    }

    @Test
    void rejectedOrForeignHistoryCannotWithdrawEvenWhenNextTaskStillRuns() {
        Task first = start(2, false);
        engine.getTaskService()
                .setVariableLocal(
                        first.getId(),
                        BpmnVariableConstants.TASK_VARIABLE_STATUS,
                        BpmTaskStatusEnum.REJECT.getStatus());
        engine.getTaskService().complete(first.getId());
        assertThat(withdrawable(first)).isFalse();
        assertThatThrownBy(() -> tasks.withdrawTask(10001L, first.getId()))
                .hasMessageContaining("已办任务");
        assertThatThrownBy(() -> tasks.withdrawTask(20002L, first.getId()))
                .hasMessageContaining("已办任务");
        assertThat(current(first).getTaskDefinitionKey()).isEqualTo("work2");
    }

    @Test
    void ordinaryApprovalCanWithdrawOnlyOnceUntilItsNewTaskIsHandled() {
        Task first = start(2, false);
        approve(first);
        Task next = current(first);
        assertThat(withdrawable(first)).isTrue();
        tasks.withdrawTask(10001L, first.getId());
        Task restored = current(first);
        assertThat(restored.getTaskDefinitionKey()).isEqualTo("work1");
        assertThat(restored.getId()).isNotEqualTo(first.getId());
        assertThat(tasks.getHistoricTask(next.getId()).getDeleteReason()).isNotNull();
        assertThat(withdrawable(first)).isFalse();
        assertThatThrownBy(() -> tasks.withdrawTask(10001L, first.getId()))
                .hasMessageContaining("下一节点");
    }

    @Test
    void nextTaskHandledInSameMillisecondCannotBeMissed() {
        engine.getProcessEngineConfiguration().getClock().setCurrentTime(new Date());
        Task first = start(3, false);
        approve(first);
        approve(current(first));
        assertThat(withdrawable(first)).isFalse();
        assertThatThrownBy(() -> tasks.withdrawTask(10001L, first.getId()))
                .hasMessageContaining("下一节点");
        assertThat(current(first).getTaskDefinitionKey()).isEqualTo("work3");
    }

    @Test
    void disabledWithdrawalIsHiddenAndRejectedByWriteEntry() {
        Task first = start(2, false);
        approve(first);
        definition.setAllowWithdrawTask(false);
        assertThat(withdrawable(first)).isFalse();
        assertThatThrownBy(() -> tasks.withdrawTask(10001L, first.getId()))
                .hasMessageContaining("不允许撤回");
    }

    @Test
    void pendingDelegationInNextNodeCannotBeDiscardedByWithdrawal() {
        Task first = start(2, false);
        approve(first);
        engine.getTaskService().delegateTask(current(first).getId(), "20002");
        assertThat(withdrawable(first)).isFalse();
        assertThatThrownBy(() -> tasks.withdrawTask(10001L, first.getId()))
                .hasMessageContaining("下一节点");
    }

    @Test
    void nextMultiInstanceWithdrawalRemovesRootAndRecreatesOnlySource() {
        Task first = start(2, true);
        approve(first);
        assertThat(
                        engine.getTaskService()
                                .createTaskQuery()
                                .processInstanceId(first.getProcessInstanceId())
                                .count())
                .isEqualTo(2);
        tasks.withdrawTask(10001L, first.getId());
        assertThat(current(first).getTaskDefinitionKey()).isEqualTo("work1");
        approve(current(first));
        var next =
                engine.getTaskService()
                        .createTaskQuery()
                        .processInstanceId(first.getProcessInstanceId())
                        .list();
        assertThat(next).hasSize(2);
        next.forEach(this::approve);
        assertThat(
                        engine.getRuntimeService()
                                .createProcessInstanceQuery()
                                .processInstanceId(first.getProcessInstanceId())
                                .count())
                .isZero();
    }

    @Test
    void failedCancellationRollsBackStatusAndReasonTogether() {
        Task first = start(1, false);
        var failingTasks = mock(BpmTaskService.class);
        doThrow(new IllegalStateException("结束阶段失败"))
                .when(failingTasks)
                .moveTaskToEnd(anyString(), anyString());
        assertThatThrownBy(
                        () ->
                                cancelService(failingTasks)
                                        .cancelProcessInstanceByStartUser(10001L, cancel(first)))
                .hasMessageContaining("结束阶段失败");
        assertThat(
                        engine.getRuntimeService()
                                .getVariable(
                                        first.getProcessInstanceId(),
                                        BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_STATUS))
                .isEqualTo(BpmProcessInstanceStatusEnum.RUNNING.getStatus());
        assertThat(
                        engine.getRuntimeService()
                                .getVariable(
                                        first.getProcessInstanceId(),
                                        BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_REASON))
                .isNull();
        assertThat(current(first)).isNotNull();
    }

    @Test
    void cancellationChecksStarterAndCannotRepeatAfterEnd() {
        Task first = start(1, false);
        var service = cancelService(tasks);
        assertThatThrownBy(() -> service.cancelProcessInstanceByStartUser(20002L, cancel(first)))
                .hasMessageContaining("不是你");
        assertThat(current(first)).isNotNull();
        service.cancelProcessInstanceByStartUser(10001L, cancel(first));
        assertThatThrownBy(() -> service.cancelProcessInstanceByStartUser(10001L, cancel(first)))
                .hasMessageContaining("不处于运行中");
        assertThat(
                        engine.getRuntimeService()
                                .createProcessInstanceQuery()
                                .processInstanceId(first.getProcessInstanceId())
                                .count())
                .isZero();
    }

    @Test
    void submittedBusinessResultCannotBeReplayedThroughWithdrawal() {
        Task first = start(2, false, "test_material");
        String id = UUID.randomUUID().toString();
        fixture.materials.add(id);
        BpmTaskCompletionIntegrationTest.transaction.executeWithoutResult(
                status -> {
                    var jdbc = BpmTaskCompletionIntegrationTest.jdbc;
                    jdbc.update(
                            "INSERT INTO public.nocode_work_draft"
                                + " (id,source_type,source_id,resource_json,object_id,values_json,creator,updater)"
                                + " VALUES"
                                + " (?,'BUSINESS_FORM','lifecycle-test','{}','lifecycle-test','{}','10001','10001')",
                            id);
                    jdbc.update(
                            "INSERT INTO public.nocode_work_submission"
                                + " (id,draft_id,idempotency_key,request_digest,material_json,creator,updater)"
                                + " VALUES"
                                + " (?,?,?,'lifecycle-test',jsonb_build_object('taskId',CAST(? AS"
                                + " text)),'10001','10001')",
                            id,
                            id,
                            id,
                            first.getId());
                    BpmTaskCompletionIntegrationTest.api.completeBusinessTask(
                            10001L,
                            new com.richuang.os.module.bpm.api.task.dto
                                    .BpmBusinessTaskCompleteReqDTO(
                                    first.getId(), id, "业务提交", Map.of()));
                });
        assertThat(withdrawable(first)).isFalse();
        assertThatThrownBy(() -> tasks.withdrawTask(10001L, first.getId()))
                .hasMessageContaining("业务结果");
        Task next = current(first);
        assertThat(tasks.getUserTaskListByReturn(next.getId())).isEmpty();
        assertThatThrownBy(
                        () ->
                                tasks.returnTask(
                                        10001L,
                                        new BpmTaskReturnReqVO()
                                                .setId(next.getId())
                                                .setTargetTaskDefinitionKey("work1")))
                .hasMessageContaining("业务办理节点");
        assertThat(engine.getTaskService().getTaskComments(next.getId())).isEmpty();
        assertThat(current(first).getTaskDefinitionKey()).isEqualTo("work2");
        assertThat(
                        BpmTaskCompletionIntegrationTest.jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_work_submission WHERE id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
    }

    @Test
    void processFinishingAfterListEligibilityStillRejectsWithdrawal() {
        Task first = start(2, false);
        approve(first);
        assertThat(withdrawable(first)).isTrue();
        approve(current(first));
        assertThatThrownBy(() -> tasks.withdrawTask(10001L, first.getId()))
                .hasMessageContaining("未运行");
        assertThat(
                        engine.getRuntimeService()
                                .createProcessInstanceQuery()
                                .processInstanceId(first.getProcessInstanceId())
                                .count())
                .isZero();
    }

    @Test
    void concurrentWithdrawalCannotCreateTwoRestoredTasks() throws Exception {
        Task first = start(2, false);
        approve(first);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var begin = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> attempt =
                    () -> {
                        ready.countDown();
                        if (!begin.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("并发夹具未就绪");
                        try {
                            tasks.withdrawTask(10001L, first.getId());
                            return true;
                        } catch (com.richuang.os.framework.common.exception.ServiceException
                                | org.flowable.common.engine.api.FlowableException expected) {
                            return false;
                        }
                    };
            var one = workers.submit(attempt);
            var two = workers.submit(attempt);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            begin.countDown();
            assertThat(
                            List.of(
                                    one.get(20, java.util.concurrent.TimeUnit.SECONDS),
                                    two.get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(current(first).getTaskDefinitionKey()).isEqualTo("work1");
        assertThat(withdrawable(first)).isFalse();
    }

    @Test
    void withdrawalCannotReplayAutomaticActionBetweenHumanTasks() {
        Task first = start(2, false, null, true);
        approve(first);
        assertThat(
                        engine.getRuntimeService()
                                .getVariable(first.getProcessInstanceId(), "automaticCount"))
                .isEqualTo(1L);
        assertThat(withdrawable(first)).isFalse();
        assertThatThrownBy(() -> tasks.withdrawTask(10001L, first.getId()))
                .hasMessageContaining("下一节点");
        assertThat(current(first).getTaskDefinitionKey()).isEqualTo("work2");
        assertThat(
                        engine.getRuntimeService()
                                .getVariable(first.getProcessInstanceId(), "automaticCount"))
                .isEqualTo(1L);
    }

    @Test
    void ordinaryReturnKeepsItsApprovedTargetButRejectsNonTaskTargets() {
        Task first = start(2, false);
        approve(first);
        Task next = current(first);
        assertThatThrownBy(
                        () ->
                                tasks.returnTask(
                                        10001L,
                                        new BpmTaskReturnReqVO()
                                                .setId(next.getId())
                                                .setTargetTaskDefinitionKey("start")))
                .isInstanceOf(com.richuang.os.framework.common.exception.ServiceException.class);
        assertThat(current(first).getId()).isEqualTo(next.getId());
        tasks.returnTask(
                10001L,
                new BpmTaskReturnReqVO()
                        .setId(next.getId())
                        .setTargetTaskDefinitionKey("work1")
                        .setReason("补充审批"));
        assertThat(current(first).getTaskDefinitionKey()).isEqualTo("work1");
    }

    @Test
    void onlyParentOwnerCanRemoveSignAndSuspendedParentCannotBeChanged() {
        Task first = start(1, false);
        var users =
                BpmTaskCompletionIntegrationTest.context.getBean(
                        com.richuang.os.module.system.api.user.AdminUserApi.class);
        var owner =
                new com.richuang.os.module.system.api.user.dto.AdminUserRespDTO()
                        .setId(10001L)
                        .setNickname("父任务负责人");
        var signer =
                new com.richuang.os.module.system.api.user.dto.AdminUserRespDTO()
                        .setId(20002L)
                        .setNickname("加签人");
        when(users.getUser(10001L)).thenReturn(owner);
        when(users.getUser(20002L)).thenReturn(signer);
        when(users.getUserList(Set.of(20002L))).thenReturn(List.of(signer));
        tasks.createSignTask(
                10001L,
                new BpmTaskSignCreateReqVO()
                        .setId(first.getId())
                        .setType("before")
                        .setUserIds(Set.of(20002L))
                        .setReason("加签回归"));
        Task child = engine.getTaskService().getSubTasks(first.getId()).getFirst();
        var remove = new BpmTaskSignDeleteReqVO().setId(child.getId()).setReason("减签回归");
        assertThatThrownBy(() -> tasks.deleteSignTask(30003L, remove)).hasMessageContaining("不是你");
        assertThatThrownBy(() -> tasks.deleteSignTask(20002L, remove)).hasMessageContaining("不是你");
        assertThat(engine.getTaskService().getSubTasks(first.getId())).hasSize(1);
        engine.getRuntimeService().suspendProcessInstanceById(first.getProcessInstanceId());
        assertThatThrownBy(() -> tasks.deleteSignTask(10001L, remove)).hasMessageContaining("挂起");
        engine.getRuntimeService().activateProcessInstanceById(first.getProcessInstanceId());
        tasks.deleteSignTask(10001L, remove);
        assertThat(engine.getTaskService().getSubTasks(first.getId())).isEmpty();
        assertThat(current(first).getAssignee()).isEqualTo("10001");
    }

    private BpmProcessInstanceCancelReqVO cancel(Task task) {
        return new BpmProcessInstanceCancelReqVO()
                .setId(task.getProcessInstanceId())
                .setReason("生命周期回归");
    }

    private BpmProcessInstanceService cancelService(BpmTaskService taskService) {
        var service = new BpmProcessInstanceServiceImpl();
        ReflectionTestUtils.setField(service, "runtimeService", engine.getRuntimeService());
        ReflectionTestUtils.setField(service, "taskService", taskService);
        ReflectionTestUtils.setField(
                service,
                "processDefinitionService",
                BpmTaskCompletionIntegrationTest.context.getBean(
                        BpmProcessDefinitionService.class));
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(
                new TransactionInterceptor(
                        BpmTaskCompletionIntegrationTest.tool.getBean(
                                PlatformTransactionManager.class),
                        new AnnotationTransactionAttributeSource()));
        return (BpmProcessInstanceService) proxy.getProxy();
    }

    private boolean withdrawable(Task task) {
        return Boolean.TRUE.equals(
                tasks.getTaskWithdrawable(10001L, List.of(tasks.getHistoricTask(task.getId())))
                        .get(task.getId()));
    }

    private void approve(Task task) {
        tasks.approveTask(
                10001L, new BpmTaskApproveReqVO().setId(task.getId()).setReason("生命周期回归"));
    }

    private Task current(Task task) {
        return engine.getTaskService()
                .createTaskQuery()
                .processInstanceId(task.getProcessInstanceId())
                .singleResult();
    }

    private Task start(int count, boolean nextMultiInstance) {
        return start(count, nextMultiInstance, null);
    }

    private Task start(int count, boolean nextMultiInstance, String firstHandler) {
        return start(count, nextMultiInstance, firstHandler, false);
    }

    private Task start(
            int count, boolean nextMultiInstance, String firstHandler, boolean automaticBetween) {
        String key = "lifecycle_" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder body =
                new StringBuilder(
                        "<startEvent id=\"start\"/><sequenceFlow id=\"s\" sourceRef=\"start\""
                                + " targetRef=\"work1\"/>");
        for (int index = 1; index <= count; index++) {
            body.append("<userTask id=\"work")
                    .append(index)
                    .append("\" name=\"生命周期审批\" flowable:assignee=\"10001\"");
            if (index == 1 && firstHandler != null)
                body.append(" business:handler=\"").append(firstHandler).append("\"");
            body.append(">");
            if (nextMultiInstance && index == 2)
                body.append(
                        "<multiInstanceLoopCharacteristics"
                            + " isSequential=\"false\"><loopCardinality>2</loopCardinality></multiInstanceLoopCharacteristics>");
            body.append("</userTask><sequenceFlow id=\"f")
                    .append(index)
                    .append("\" sourceRef=\"work")
                    .append(index)
                    .append("\" targetRef=\"")
                    .append(index == count ? "end" : "work" + (index + 1))
                    .append("\"/>");
        }
        body.append("<endEvent id=\"end\"/>");
        String flowBody = body.toString();
        if (automaticBetween)
            flowBody =
                    flowBody.replace(
                            "<sequenceFlow id=\"f1\" sourceRef=\"work1\" targetRef=\"work2\"/>",
                            "<sequenceFlow id=\"f1\" sourceRef=\"work1\""
                                + " targetRef=\"automatic\"/><serviceTask id=\"automatic\""
                                + " flowable:expression=\"${execution.setVariable('automaticCount',"
                                + " execution.getVariable('automaticCount') + 1)}\"/><sequenceFlow"
                                + " id=\"automaticNext\" sourceRef=\"automatic\""
                                + " targetRef=\"work2\"/>");
        String xml =
                "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                        + " xmlns:business=\"https://richuang.com/schema/bpmn/business-task\""
                        + " xmlns:flowable=\"http://flowable.org/bpmn\""
                        + " targetNamespace=\"lifecycle\"><process id=\""
                        + key
                        + "\" isExecutable=\"true\">"
                        + flowBody
                        + "</process></definitions>";
        var deployed =
                engine.getRepositoryService()
                        .createDeployment()
                        .addString(key + ".bpmn20.xml", xml)
                        .deploy();
        fixture.deployments.add(deployed.getId());
        org.flowable.common.engine.impl.identity.Authentication.setAuthenticatedUserId("10001");
        try {
            var instance =
                    engine.getRuntimeService()
                            .startProcessInstanceByKey(
                                    key,
                                    Map.of(
                                            "automaticCount",
                                            0L,
                                            BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_STATUS,
                                            BpmProcessInstanceStatusEnum.RUNNING.getStatus()));
            return engine.getTaskService()
                    .createTaskQuery()
                    .processInstanceId(instance.getId())
                    .singleResult();
        } finally {
            org.flowable.common.engine.impl.identity.Authentication.setAuthenticatedUserId(null);
        }
    }
}
