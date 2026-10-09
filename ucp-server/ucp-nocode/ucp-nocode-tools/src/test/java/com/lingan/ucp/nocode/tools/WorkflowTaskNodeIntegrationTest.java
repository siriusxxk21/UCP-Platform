package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.module.bpm.framework.flowable.core.enums.BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_START_USER_ID;
import static com.lingan.ucp.module.bpm.framework.flowable.core.enums.BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_STATUS;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.framework.tenant.core.context.TenantContextHolder;
import com.lingan.ucp.module.bpm.api.task.BpmProcessTaskApiImpl;
import com.lingan.ucp.module.bpm.api.task.BpmTaskCenterNodeApi;
import com.lingan.ucp.module.bpm.api.task.BpmTaskCenterNodeApiImpl;
import com.lingan.ucp.module.bpm.api.task.dto.BpmTaskCenterNodeExecutionDTO;
import com.lingan.ucp.module.bpm.controller.admin.task.vo.instance.BpmProcessInstanceCancelReqVO;
import com.lingan.ucp.module.bpm.controller.admin.task.vo.task.BpmTaskApproveReqVO;
import com.lingan.ucp.module.bpm.controller.admin.task.vo.task.BpmTaskPageReqVO;
import com.lingan.ucp.module.bpm.dal.dataobject.definition.BpmFormDO;
import com.lingan.ucp.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import com.lingan.ucp.module.bpm.dal.mysql.task.BpmProcessInstanceCopyMapper;
import com.lingan.ucp.module.bpm.framework.flowable.core.listener.BpmTaskCenterNodeListener;
import com.lingan.ucp.module.bpm.service.definition.BpmModelService;
import com.lingan.ucp.module.bpm.service.definition.BpmProcessDefinitionService;
import com.lingan.ucp.module.bpm.service.definition.BpmTaskCenterNodeModelService;
import com.lingan.ucp.module.bpm.service.definition.BpmTaskCenterNodeModelServiceImpl;
import com.lingan.ucp.module.bpm.service.task.BpmProcessInstanceService;
import com.lingan.ucp.module.bpm.service.task.BpmProcessInstanceServiceImpl;
import com.lingan.ucp.module.bpm.service.task.BpmTaskCenterNodeServiceImpl;
import com.lingan.ucp.module.bpm.service.task.BpmTaskService;
import com.lingan.ucp.module.msg.api.IMsgSendService;
import com.lingan.ucp.module.system.api.permission.PermissionApi;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.ApplicationRecords;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.api.TaskClaims;
import com.lingan.ucp.nocode.api.TaskGuidance;
import com.lingan.ucp.nocode.api.TaskPlanning;
import com.lingan.ucp.nocode.api.TaskWorkEntries;
import com.lingan.ucp.nocode.api.workflow.WorkflowTaskGuard;
import com.lingan.ucp.nocode.api.workflow.WorkflowTaskNodes;
import com.lingan.ucp.nocode.api.workflow.WorkflowTaskNodes.Configuration;
import com.lingan.ucp.nocode.api.workflow.WorkflowTaskNodes.Person;
import com.lingan.ucp.nocode.api.workflow.WorkflowTaskNodes.PersonSource;
import com.lingan.ucp.nocode.api.workflow.WorkflowTaskNodes.Role;
import com.lingan.ucp.nocode.api.workflow.WorkflowTaskNodes.Source;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskDataPolicies;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskWorkEntryService;
import com.lingan.ucp.nocode.workflow.adapter.bpm.WorkflowTaskNodeHandler;
import com.lingan.ucp.nocode.workflow.dal.dataobject.task.WorkflowTaskNodeDO;
import com.lingan.ucp.nocode.workflow.dal.mapper.task.WorkflowTaskNodeMapper;
import com.lingan.ucp.nocode.workflow.service.task.WorkflowTaskNodeService;
import com.lingan.ucp.nocode.workflow.service.task.WorkflowTaskNodeServiceImpl;

import org.flowable.engine.ProcessEngine;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentBuilder;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 工作流任务节点专项联合测试：真实开发库、任务中心、发布冻结和 Flowable 等待/审批链路。
 *
 * <p>账号与权限目录沿用已有测试替身，不冒充真实登录验收；仅清理本类 UUID 部署及其任务。
 */
class WorkflowTaskNodeIntegrationTest {
    private static final long TENANT = 1L;
    private static final long PUBLISHER = 10001L;
    private static final long INITIATOR = 25001L;
    private static final long WORKER = 25002L;
    private static final long REVIEWER = 25003L;
    private static final long STRANGER = 25004L;
    private static AnnotationConfigApplicationContext workflowContext;
    private static ProcessEngine engine;
    private static TaskCenterService tasks;
    private static WorkflowTaskNodeService workflow;
    private static WorkflowTaskNodeMapper bindings;
    private static BpmTaskCenterNodeModelService models;
    private WorkDraftIntegrationTest business;
    private final Set<String> deployments = new LinkedHashSet<>();
    private final Set<String> processes = new LinkedHashSet<>();
    private final Set<String> templates = new LinkedHashSet<>();
    private final String marker = "workflow_task_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeAll
    static void open() throws Exception {
        connect();
        if (!session.getConfiguration().hasMapper(WorkflowTaskNodeMapper.class))
            session.getConfiguration().addMapper(WorkflowTaskNodeMapper.class);
        workflowContext = new AnnotationConfigApplicationContext();
        workflowContext.setParent(servicesContext);
        workflowContext.register(Fixture.class);
        workflowContext.addBeanFactoryPostProcessor(
                factory ->
                        factory.getBeanDefinition(BpmProcessTaskApiImpl.class.getName())
                                .setPrimary(true));
        workflowContext.refresh();
        engine = workflowContext.getBean(ProcessEngine.class);
        tasks = servicesContext.getBean(TaskCenterService.class);
        workflow = workflowContext.getBean(WorkflowTaskNodeService.class);
        bindings = workflowContext.getBean(WorkflowTaskNodeMapper.class);
        models = workflowContext.getBean(BpmTaskCenterNodeModelService.class);
        // 父夹具中的 runtime 延迟发现真实来源保护；生产使用同一容器，不需要此桥接注册。
        servicesContext
                .getBeanFactory()
                .registerSingleton(
                        "workflowTaskNodeIntegrationGuard",
                        new WorkflowTaskGuard() {
                            @Override
                            public void requireActive(String rootId) {
                                workflow.requireActive(rootId);
                            }

                            @Override
                            public Map<String, String> readOnlyReasons(Collection<String> rootIds) {
                                return workflow.readOnlyReasons(rootIds);
                            }
                        });
        when(workflowContext.getBean(BpmModelService.class).getBpmnModelByDefinitionId(anyString()))
                .thenAnswer(
                        call -> engine.getRepositoryService().getBpmnModel(call.getArgument(0)));
        when(workflowContext
                        .getBean(BpmProcessDefinitionService.class)
                        .getProcessDefinitionBpmnModel(anyString()))
                .thenAnswer(
                        call -> engine.getRepositoryService().getBpmnModel(call.getArgument(0)));
        when(workflowContext
                        .getBean(BpmProcessInstanceService.class)
                        .getProcessInstance(anyString()))
                .thenAnswer(
                        call ->
                                engine.getRuntimeService()
                                        .createProcessInstanceQuery()
                                        .processInstanceId(call.getArgument(0))
                                        .includeProcessVariables()
                                        .singleResult());
    }

    @AfterAll
    static void closeContexts() {
        if (servicesContext != null)
            servicesContext
                    .getDefaultListableBeanFactory()
                    .destroySingleton("workflowTaskNodeIntegrationGuard");
        if (workflowContext != null) workflowContext.close();
        close();
    }

    @BeforeEach
    void setup() {
        TenantContextHolder.setTenantId(TENANT);
        when(servicesContext.getBean(IMsgSendService.class).send(any())).thenReturn(999L);
        for (AdminUserApi users :
                List.of(
                        servicesContext.getBean(AdminUserApi.class),
                        workflowContext.getBean(AdminUserApi.class))) {
            when(users.getUser(anyLong())).thenAnswer(call -> activeUser(call.getArgument(0)));
            // 固定执行人的生产入口使用目录批量校验，不能把空 mock 当成停用人员验证通过。
            doAnswer(
                            call -> {
                                Collection<Long> ids = call.getArgument(0);
                                for (Long id : ids) {
                                    AdminUserRespDTO user = users.getUser(id);
                                    if (user == null
                                            || !Integer.valueOf(0).equals(user.getStatus()))
                                        throw com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid(
                                                "测试目录人员不存在或已停用");
                                }
                                return null;
                            })
                    .when(users)
                    .validateUserList(anyCollection());
        }
        PermissionApi permissions = servicesContext.getBean(PermissionApi.class);
        when(permissions.hasAnyPermissions(anyLong(), any(String[].class))).thenReturn(false);
        when(permissions.hasAnyPermissions(PUBLISHER, "bpm:process-instance:manager-query"))
                .thenReturn(true);
        when(permissions.hasAnyPermissions(INITIATOR, "bpm:process-instance:query"))
                .thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        writeFailure.clear();
        // 先查询自有流程对应的根任务，流程清理不影响用户已有流程或任务。
        Set<String> roots = new LinkedHashSet<>();
        for (String process : processes) {
            roots.addAll(
                    jdbc.queryForList(
                            "select task_id from public.nocode_workflow_task_node where"
                                    + " process_instance_id=? and task_id is not null",
                            String.class,
                            process));
        }
        for (String deployment : deployments)
            engine.getRepositoryService().deleteDeployment(deployment, true);
        for (String process : processes)
            jdbc.update(
                    "delete from public.nocode_workflow_task_node where process_instance_id=?",
                    process);
        for (String root : roots) {
            for (String table :
                    List.of(
                            "nocode_task_entry_record",
                            "nocode_task_entry_binding",
                            "nocode_task_plan",
                            "nocode_task_comment"))
                jdbc.update(
                        "delete from public."
                                + table
                                + " where task_id in (select id from public.nocode_task_instance"
                                + " where root_id=?)",
                        root);
            jdbc.update("delete from public.nocode_task_event where root_id=?", root);
            jdbc.update("delete from public.nocode_task_instance where root_id=?", root);
        }
        for (String template : templates) {
            jdbc.update(
                    "delete from public.nocode_task_template_version where template_id=?",
                    template);
            jdbc.update("delete from public.nocode_task_template where id=?", template);
        }
        if (business != null) business.cleanup();
        TenantContextHolder.clear();
    }

    @Test
    void deploymentFreezesVersionWithoutCreatingTasksAndLaterTemplateEditsDoNotLeak()
            throws Exception {
        Template draft =
                tasks.saveTemplate(
                        new SaveTemplate(
                                null,
                                null,
                                marker,
                                null,
                                List.of(node("child", "root", "版本一子项", WORKER, null)),
                                Kind.ORDINARY,
                                node("root", null, "版本一", WORKER, null)),
                        PUBLISHER);
        templates.add(draft.id());
        tasks.publish(new PublishTemplate(draft.id(), draft.revision()), PUBLISHER);
        Configuration configuration =
                new Configuration(
                        1,
                        Source.TEMPLATE,
                        draft.id(),
                        1,
                        node("root", null, "版本一", WORKER, null),
                        List.of(node("child", "root", "版本一子项", WORKER, null)),
                        List.of(),
                        null,
                        null);
        long before = countTasks();
        Deployed deployed = deploy(configuration, null, false);
        assertThat(countTasks()).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_workflow_task_node where"
                                        + " process_definition_id=?",
                                Long.class,
                                deployed.definitionId()))
                .isZero();
        Template head =
                tasks.templates(PUBLISHER).stream()
                        .filter(value -> value.id().equals(draft.id()))
                        .findFirst()
                        .orElseThrow();
        Template changed =
                tasks.saveTemplate(
                        new SaveTemplate(
                                head.id(),
                                head.revision(),
                                marker + "v2",
                                null,
                                List.of(node("child", "root", "版本二子项", WORKER, null)),
                                Kind.ORDINARY,
                                node("root", null, "版本二", WORKER, null)),
                        PUBLISHER);
        tasks.publish(new PublishTemplate(changed.id(), changed.revision()), PUBLISHER);
        Running running = start(deployed, Map.of());
        Row root = activate(running);
        Detail detail = tasks.detail(root.id(), WORKER);
        assertThat(root.templateVersion()).isEqualTo(1);
        assertThat(detail.nodes())
                .extracting(Row::title)
                .contains(marker + "版本一子项")
                .doesNotContain(marker + "版本二子项");
        assertNoPlans(root.id());
    }

    @Test
    void unreviewedCompletionAdvancesToExistingApprovalOnlyAfterAllChildrenFinish()
            throws Exception {
        Running running =
                start(
                        deploy(
                                custom(
                                        node("root", null, marker, WORKER, null),
                                        List.of(node("child", "root", "执行子项", WORKER, null)),
                                        List.of()),
                                null,
                                false),
                        Map.of());
        Row root = activate(running);
        BpmTaskService approvals = workflowContext.getBean(BpmTaskService.class);
        String definitionId =
                engine.getRuntimeService()
                        .createProcessInstanceQuery()
                        .processInstanceId(running.processId())
                        .singleResult()
                        .getProcessDefinitionId();
        BpmTaskPageReqVO page = new BpmTaskPageReqVO();
        page.setPageNo(1);
        page.setPageSize(10);
        page.setProcessDefinitionKey(
                engine.getRepositoryService().getProcessDefinition(definitionId).getKey());
        // 任务中心办理期间不额外生成审批待办；只查询本次 UUID 流程定义。
        assertThat(approvals.getTaskTodoPage(REVIEWER, page).getTotal()).isZero();
        assertThat(approvals.getTaskTodoPage(WORKER, page).getTotal()).isZero();
        transition(root.id(), Action.START, WORKER);
        Row child =
                tasks.detail(root.id(), WORKER).nodes().stream()
                        .filter(row -> row.parentId() != null)
                        .findFirst()
                        .orElseThrow();
        retry(running);
        assertWaiting(running);
        transition(child.id(), Action.START, WORKER);
        transition(child.id(), Action.COMPLETE, WORKER);
        assertThat(tasks.detail(root.id(), WORKER).task().status())
                .isEqualTo(State.COMPLETED.name());
        retry(running);
        Task approval = assertApproval(running);
        assertThat(approvals.getTaskTodoPage(REVIEWER, page).getTotal()).isEqualTo(1);
        assertThat(approvals.getTaskTodoPage(REVIEWER, page).getList())
                .extracting(Task::getId)
                .containsExactly(approval.getId());
        assertThat(approvals.getTaskTodoPage(WORKER, page).getTotal()).isZero();
        assertThat(approvals.getTaskDonePage(REVIEWER, page).getTotal()).isZero();
        BpmTaskApproveReqVO command = new BpmTaskApproveReqVO();
        command.setId(approval.getId());
        command.setReason("任务完成后原审批继续办理");
        command.setVariables(Map.of());
        approvals.approveTask(REVIEWER, command);
        assertThat(approvals.getTaskTodoPage(REVIEWER, page).getTotal()).isZero();
        assertThat(approvals.getTaskDonePage(REVIEWER, page).getTotal()).isEqualTo(1);
        assertThat(approvals.getTaskDonePage(REVIEWER, page).getList())
                .extracting(HistoricTaskInstance::getId)
                .containsExactly(approval.getId());
        assertThat(approvals.getTaskDonePage(WORKER, page).getTotal()).isZero();
        assertThat(
                        engine.getRuntimeService()
                                .createProcessInstanceQuery()
                                .processInstanceId(running.processId())
                                .count())
                .isZero();
        assertNoPlans(root.id());
    }

    @Test
    void nestedTasksRollUpEveryAncestorOnlyAfterAllLeafTasksComplete() throws Exception {
        List<NodeInput> nodes =
                List.of(
                        node("stage", "root", "多层阶段", WORKER, null),
                        node("firstLeaf", "stage", "第一末级任务", WORKER, null),
                        node("lastLeaf", "stage", "最后末级任务", WORKER, null));
        Running running =
                start(
                        deploy(
                                custom(node("root", null, marker, WORKER, null), nodes, List.of()),
                                null,
                                false),
                        Map.of());
        Row root = activate(running);
        Detail group = tasks.detail(root.id(), WORKER);
        Row stage = taskNamed(group, "多层阶段");
        Row first = taskNamed(group, "第一末级任务");
        Row last = taskNamed(group, "最后末级任务");
        transition(root.id(), Action.START, WORKER);
        transition(stage.id(), Action.START, WORKER);
        assertThatThrownBy(() -> transition(root.id(), Action.COMPLETE, WORKER))
                .isInstanceOf(RuntimeException.class);
        transition(first.id(), Action.START, WORKER);
        transition(first.id(), Action.COMPLETE, WORKER);
        assertThat(tasks.detail(stage.id(), WORKER).task().status())
                .isEqualTo(State.RUNNING.name());
        assertThat(tasks.detail(root.id(), WORKER).task().status()).isEqualTo(State.RUNNING.name());
        retry(running);
        assertWaiting(running);
        transition(last.id(), Action.START, WORKER);
        transition(last.id(), Action.COMPLETE, WORKER);
        assertThat(tasks.detail(stage.id(), WORKER).task().status())
                .isEqualTo(State.COMPLETED.name());
        assertThat(tasks.detail(root.id(), WORKER).nodes())
                .allMatch(row -> State.COMPLETED.name().equals(row.status()));
        assertThat(tasks.detail(root.id(), WORKER).task().status())
                .isEqualTo(State.COMPLETED.name());
        retry(running);
        assertApproval(running);
        assertNoPlans(root.id());
    }

    @Test
    void requiredBusinessAndFeedbackMaterialsBlockCompletionAndWorkflowAdvanceUntilValid()
            throws Exception {
        business = new WorkDraftIntegrationTest();
        business.setup();
        Binding resource =
                new Binding(
                        business.resource.applicationId(), business.resource.resourceId(), null);
        TaskWorkEntries.Config requiredBusiness =
                new TaskWorkEntries.Config(
                        TaskDataPolicies.BUSINESS,
                        "必填业务资料",
                        resource,
                        TaskWorkEntries.DataMode.ROOT_SHARED,
                        null,
                        null,
                        null,
                        null,
                        true,
                        false);
        TaskWorkEntries.Config requiredFeedback =
                new TaskWorkEntries.Config(
                        "requiredFeedback",
                        "必填过程反馈",
                        resource,
                        TaskWorkEntries.DataMode.ROOT_SHARED,
                        null,
                        null,
                        null,
                        null,
                        true,
                        false);
        NodeInput input =
                new NodeInput(
                        "root",
                        null,
                        marker + "材料总任务",
                        null,
                        WORKER,
                        null,
                        null,
                        new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                        List.of(),
                        resource,
                        null,
                        List.of(requiredBusiness, requiredFeedback),
                        AssignmentMode.ASSIGNED,
                        List.of(),
                        new DataPolicy(1, DataAccessMode.GROUP, DataAccessMode.GROUP),
                        null);
        Running running =
                start(
                        deploy(
                                custom(
                                        input,
                                        List.of(node("child", "root", "材料执行任务", WORKER, null)),
                                        List.of()),
                                null,
                                false),
                        Map.of());
        Row root = activate(running);
        Row child = taskNamed(tasks.detail(root.id(), WORKER), "材料执行任务");
        TaskWorkEntryService entries = servicesContext.getBean(TaskWorkEntryService.class);
        transition(root.id(), Action.START, WORKER);
        transition(child.id(), Action.START, WORKER);
        assertThat(tasks.readiness(child.id(), WORKER).checks())
                .filteredOn(check -> !check.passed())
                .extracting(TaskGuidance.Check::code)
                .contains(TaskGuidance.CheckCode.BUSINESS, TaskGuidance.CheckCode.FEEDBACK);
        assertThatThrownBy(() -> transition(child.id(), Action.COMPLETE, WORKER))
                .hasMessageContaining("至少");
        assertThatThrownBy(
                        () ->
                                entries.save(
                                        new TaskWorkEntries.Save(
                                                child.id(),
                                                TaskDataPolicies.BUSINESS,
                                                null,
                                                materialInput(Map.of())),
                                        WORKER))
                .isInstanceOf(RuntimeException.class);
        assertThat(
                        entries.page(
                                        new TaskWorkEntries.Query(
                                                child.id(),
                                                TaskDataPolicies.BUSINESS,
                                                false,
                                                false,
                                                1,
                                                20,
                                                null),
                                        WORKER)
                                .getList())
                .isEmpty();
        assertThat(tasks.detail(root.id(), WORKER).task().status()).isEqualTo(State.RUNNING.name());
        retry(running);
        assertWaiting(running);

        entries.save(
                new TaskWorkEntries.Save(
                        child.id(),
                        TaskDataPolicies.BUSINESS,
                        null,
                        materialInput(Map.of(business.nameField, "已校验业务资料"))),
                WORKER);
        assertThatThrownBy(() -> transition(child.id(), Action.COMPLETE, WORKER))
                .hasMessageContaining("必填过程反馈");
        assertThat(tasks.detail(child.id(), WORKER).task().status())
                .isEqualTo(State.RUNNING.name());
        assertThat(tasks.detail(root.id(), WORKER).task().status()).isEqualTo(State.RUNNING.name());
        retry(running);
        assertWaiting(running);

        entries.save(
                new TaskWorkEntries.Save(
                        child.id(),
                        "requiredFeedback",
                        null,
                        materialInput(Map.of(business.nameField, "已校验反馈材料"))),
                WORKER);
        transition(child.id(), Action.COMPLETE, WORKER);
        assertThat(tasks.detail(root.id(), WORKER).task().status())
                .isEqualTo(State.COMPLETED.name());
        assertThat(entries.materials(root.id(), WORKER))
                .extracting(TaskWorkEntries.Material::entryKey)
                .containsExactlyInAnyOrder(TaskDataPolicies.BUSINESS, "requiredFeedback");
        retry(running);
        assertApproval(running);
        assertNoPlans(root.id());
    }

    @Test
    void acceptanceRejectResubmitAndApproveAreRequiredBeforeAdvancing() throws Exception {
        Running running = simpleRun(REVIEWER);
        Row root = activate(running);
        transition(root.id(), Action.START, WORKER);
        assertThat(transition(root.id(), Action.COMPLETE, WORKER).status())
                .isEqualTo(State.PENDING_ACCEPTANCE.name());
        retry(running);
        assertWaiting(running);
        transition(root.id(), Action.REJECT, REVIEWER);
        retry(running);
        assertWaiting(running);
        assertThat(tasks.detail(root.id(), WORKER).task().status()).isEqualTo(State.RUNNING.name());
        transition(root.id(), Action.COMPLETE, WORKER);
        assertThatThrownBy(() -> transition(root.id(), Action.APPROVE, WORKER))
                .isInstanceOf(RuntimeException.class);
        transition(root.id(), Action.APPROVE, REVIEWER);
        retry(running);
        assertApproval(running);
    }

    @Test
    void openClaimKeepsTaskUnassignedUntilEligibleWorkerClaimsWithoutPlanSideEffects()
            throws Exception {
        NodeInput open =
                new NodeInput(
                        "root",
                        null,
                        marker,
                        null,
                        null,
                        null,
                        null,
                        new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                        List.of(),
                        null,
                        null,
                        null,
                        AssignmentMode.OPEN,
                        List.of(WORKER),
                        null,
                        null);
        Running running = start(deploy(custom(open, List.of(), List.of()), null, false), Map.of());
        Row root = activate(running);
        assertThat(root.assigneeId()).isNull();
        assertThatThrownBy(
                        () -> tasks.claim(new Claim(root.id(), root.revision(), key()), STRANGER))
                .isInstanceOf(RuntimeException.class);
        Row claimed = tasks.claim(new Claim(root.id(), root.revision(), key()), WORKER).task();
        assertThat(claimed.assigneeId()).isEqualTo(WORKER);
        assertNoPlans(root.id());
        transition(root.id(), Action.START, WORKER);
        transition(root.id(), Action.COMPLETE, WORKER);
        retry(running);
        assertApproval(running);
        assertNoPlans(root.id());
    }

    @Test
    void dynamicInitiatorAndSinglePersonFieldResolveAtArrivalAndStayFrozen() throws Exception {
        Configuration config =
                custom(
                        node("root", null, marker, PUBLISHER, REVIEWER),
                        List.of(),
                        List.of(
                                new Person("root", Role.ASSIGNEE, PersonSource.INITIATOR, null),
                                new Person(
                                        "root",
                                        Role.ACCEPTOR,
                                        PersonSource.FORM_FIELD,
                                        "reviewer")));
        BpmFormDO form = memberForm();
        Running running = start(deploy(config, form, false), Map.of("reviewer", REVIEWER));
        engine.getRuntimeService().setVariable(running.processId(), "reviewer", STRANGER);
        Row root = activate(running);
        assertThat(root.assigneeId()).isEqualTo(INITIATOR);
        assertThat(root.acceptorId()).isEqualTo(REVIEWER);
        transition(root.id(), Action.START, INITIATOR);
        transition(root.id(), Action.COMPLETE, INITIATOR);
        transition(root.id(), Action.APPROVE, REVIEWER);
        retry(running);
        assertApproval(running);
    }

    @Test
    void invalidPeopleFieldIsRejectedAtPublishOrArrivalWithoutPartialTasks() throws Exception {
        Configuration config =
                custom(
                        node("root", null, marker, WORKER, null),
                        List.of(),
                        List.of(
                                new Person(
                                        "root",
                                        Role.ASSIGNEE,
                                        PersonSource.FORM_FIELD,
                                        "reviewer")));
        assertThatThrownBy(() -> deploy(config, null, false)).isInstanceOf(RuntimeException.class);
        Deployed deployed = deploy(config, memberForm(), false);
        long before = countTasks();
        Running running = start(deployed, Map.of("reviewer", List.of(WORKER, REVIEWER)));
        retry(running);
        WorkflowTaskNodeDO binding = binding(running);
        assertThat(binding.getTaskId()).isNull();
        assertThat(binding.getLastError()).isNotBlank();
        assertThat(countTasks()).isEqualTo(before);
        assertWaiting(running);
    }

    @Test
    void inactiveAccountCreationFailureRetriesExactlyOnceAfterAccountRecovers() throws Exception {
        Running running = simpleRun(null);
        when(servicesContext.getBean(AdminUserApi.class).getUser(WORKER)).thenReturn(null);
        retry(running);
        assertThat(binding(running).getTaskId()).isNull();
        assertThat(binding(running).getLastError()).isNotBlank();
        when(servicesContext.getBean(AdminUserApi.class).getUser(WORKER))
                .thenReturn(activeUser(WORKER));
        Row root = activate(running);
        retry(running);
        assertThat(binding(running).getTaskId()).isEqualTo(root.id());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_instance where root_id=?"
                                        + " and parent_id is null",
                                Long.class,
                                root.id()))
                .isEqualTo(1L);
        assertNoPlans(root.id());
    }

    @Test
    void taskPauseResumeKeepsProcessWaitingAndCancelledTaskNeverAdvances() throws Exception {
        Running paused = simpleRun(null);
        Row root = activate(paused);
        transition(root.id(), Action.START, WORKER);
        transition(root.id(), Action.PAUSE, WORKER);
        retry(paused);
        assertWaiting(paused);
        assertThatThrownBy(() -> transition(root.id(), Action.COMPLETE, WORKER))
                .isInstanceOf(RuntimeException.class);
        transition(root.id(), Action.RESUME, WORKER);
        transition(root.id(), Action.COMPLETE, WORKER);
        retry(paused);
        assertApproval(paused);

        Running cancelled = simpleRun(null);
        Row cancelledRoot = activate(cancelled);
        transition(cancelledRoot.id(), Action.CANCEL, WORKER);
        retry(cancelled);
        assertWaiting(cancelled);
        assertThat(tasks.detail(cancelledRoot.id(), WORKER).task().status())
                .isEqualTo(State.CANCELLED.name());
    }

    @Test
    void suspendedProcessRejectsTaskMutationAndResumesWithoutDuplicateTask() throws Exception {
        Running running = simpleRun(null);
        Row root = activate(running);
        engine.getRuntimeService().suspendProcessInstanceById(running.processId());
        assertThatThrownBy(() -> transition(root.id(), Action.START, WORKER))
                .isInstanceOf(RuntimeException.class);
        retry(running);
        assertThat(binding(running).getTaskId()).isEqualTo(root.id());
        engine.getRuntimeService().activateProcessInstanceById(running.processId());
        transition(root.id(), Action.START, WORKER);
        transition(root.id(), Action.COMPLETE, WORKER);
        retry(running);
        assertApproval(running);
    }

    @Test
    void terminatedProcessInvalidatesBindingAndKeepsTaskHistoryReadOnly() throws Exception {
        Running running = simpleRun(null);
        Row root = activate(running);
        engine.getRuntimeService().deleteProcessInstance(running.processId(), "专项夹具终止");
        assertThatThrownBy(() -> transition(root.id(), Action.START, WORKER))
                .isInstanceOf(RuntimeException.class);
        retry(running);
        assertThat(binding(running).getState())
                .isEqualTo(WorkflowTaskNodes.State.INVALIDATED.name());
        assertThat(tasks.detail(root.id(), WORKER).task().id()).isEqualTo(root.id());
        assertThat(
                        engine.getTaskService()
                                .createTaskQuery()
                                .processInstanceId(running.processId())
                                .count())
                .isZero();
    }

    @ParameterizedTest
    @CsvSource({"false, true", "false, false", "true, true", "true, false"})
    void blockedProcessRejectsLegacyPlanWrites(boolean terminated, boolean include)
            throws Exception {
        Running running = simpleRun(null);
        Row root = activate(running);
        LocalDate date = LocalDate.now();
        if (!include) tasks.plan(new SavePlan(List.of(root.id()), Period.DAY, date, true), WORKER);
        blockProcess(running, terminated);
        assertPlanningWriteRejected(
                root.id(),
                terminated,
                () ->
                        tasks.plan(
                                new SavePlan(List.of(root.id()), Period.DAY, date, include),
                                WORKER));
    }

    @ParameterizedTest
    @CsvSource({"false, true", "false, false", "true, true", "true, false"})
    void blockedProcessRejectsScheduleWrites(boolean terminated, boolean arrange) throws Exception {
        Running running = simpleRun(null);
        Row root = activate(running);
        LocalDate date = LocalDate.now();
        if (!arrange) tasks.plan(new SavePlan(List.of(root.id()), Period.DAY, date, true), WORKER);
        TaskPlanning.Item context =
                tasks.planContext(
                                new TaskPlanning.ContextQuery(
                                        List.of(root.id()), TaskPlanning.Target.SELF),
                                WORKER)
                        .items()
                        .getFirst();
        TaskPlanning.Change command =
                new TaskPlanning.Change(
                        List.of(root.id()),
                        TaskPlanning.Target.SELF,
                        arrange ? TaskPlanning.Action.ARRANGE : TaskPlanning.Action.CANCEL,
                        Period.DAY,
                        date,
                        null,
                        arrange ? List.of() : context.plans().stream().map(Plan::id).toList(),
                        Map.of(root.id(), context.version()));
        blockProcess(running, terminated);
        assertPlanningWriteRejected(root.id(), terminated, () -> tasks.schedule(command, WORKER));
    }

    @ParameterizedTest
    @CsvSource({"false, true", "false, false", "true, true", "true, false"})
    void blockedProcessRejectsChecklistWrites(boolean terminated, boolean add) throws Exception {
        Running running = simpleRun(null);
        Row root = activate(running);
        TaskPlanning.ContextQuery query =
                new TaskPlanning.ContextQuery(List.of(root.id()), TaskPlanning.Target.SELF);
        TaskPlanning.ChecklistContext context = tasks.checklistContext(query, WORKER);
        if (!add) {
            tasks.checklist(
                    new TaskPlanning.ChecklistChange(
                            List.of(root.id()),
                            TaskPlanning.Target.SELF,
                            TaskPlanning.ChecklistAction.ADD,
                            Period.DAY,
                            context.today(),
                            List.of(),
                            Map.of(root.id(), context.items().getFirst().version()),
                            key()),
                    WORKER);
            context = tasks.checklistContext(query, WORKER);
        }
        TaskPlanning.ChecklistItem item = context.items().getFirst();
        TaskPlanning.ChecklistChange command =
                new TaskPlanning.ChecklistChange(
                        List.of(root.id()),
                        TaskPlanning.Target.SELF,
                        add
                                ? TaskPlanning.ChecklistAction.ADD
                                : TaskPlanning.ChecklistAction.REMOVE,
                        Period.DAY,
                        context.today(),
                        add ? List.of() : item.todayPlans().stream().map(Plan::id).toList(),
                        Map.of(root.id(), item.version()),
                        key());
        blockProcess(running, terminated);
        assertPlanningWriteRejected(root.id(), terminated, () -> tasks.checklist(command, WORKER));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blockedProcessDisablesOpenGroupClaimAndPreview(boolean terminated) throws Exception {
        NodeInput open =
                new NodeInput(
                        "root",
                        null,
                        marker,
                        null,
                        null,
                        null,
                        null,
                        new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                        List.of(),
                        null,
                        null,
                        null,
                        AssignmentMode.OPEN,
                        List.of(WORKER),
                        null,
                        null);
        Running running = start(deploy(custom(open, List.of(), List.of()), null, false), Map.of());
        Row root = activate(running);
        TaskClaims.Query query = new TaskClaims.Query(marker, null, null, 1, 20);
        assertThat(tasks.claimableGroups(query, WORKER).getList().getFirst().canClaimGroup())
                .isTrue();
        blockProcess(running, terminated);
        TaskClaims.Group group = tasks.claimableGroups(query, WORKER).getList().getFirst();
        assertThat(group.canClaimGroup()).isFalse();
        assertThat(group.wholeClaimCount()).isZero();
        assertThat(group.remainingClaimCount()).isZero();
        assertThatThrownBy(() -> tasks.claimPreview(new TaskClaims.Root(root.id()), WORKER))
                .hasMessageContaining(terminated ? "所属流程已结束" : "所属流程已挂起");
        assertThatThrownBy(() -> tasks.claim(new Claim(root.id(), root.revision(), key()), WORKER))
                .hasMessageContaining(terminated ? "所属流程已结束" : "所属流程已挂起");
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void blockedProcessDisablesRunningAndAcceptanceActions(boolean terminated, boolean accepting)
            throws Exception {
        Running running = simpleRun(accepting ? REVIEWER : null);
        Row root = activate(running);
        transition(root.id(), Action.START, WORKER);
        if (accepting) transition(root.id(), Action.COMPLETE, WORKER);
        blockProcess(running, terminated);
        Row worker = tasks.detail(root.id(), WORKER).task();
        assertThat(worker.canExecute()).isFalse();
        assertThat(worker.canPause()).isFalse();
        if (accepting) {
            assertThat(tasks.detail(root.id(), REVIEWER).task().canAccept()).isFalse();
            assertThatThrownBy(() -> transition(root.id(), Action.APPROVE, REVIEWER))
                    .hasMessageContaining(terminated ? "所属流程已结束" : "所属流程已挂起");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blockedProcessProjectsReadonlyCapabilitiesAndPlanReasons(boolean terminated)
            throws Exception {
        Running running = simpleRun(null);
        Row root = activate(running);
        TaskPlanning.ContextQuery contextQuery =
                new TaskPlanning.ContextQuery(List.of(root.id()), TaskPlanning.Target.SELF);
        TaskPlanning.ChecklistContext before = tasks.checklistContext(contextQuery, WORKER);
        tasks.checklist(
                new TaskPlanning.ChecklistChange(
                        List.of(root.id()),
                        TaskPlanning.Target.SELF,
                        TaskPlanning.ChecklistAction.ADD,
                        Period.DAY,
                        before.today(),
                        List.of(),
                        Map.of(root.id(), before.items().getFirst().version()),
                        key()),
                WORKER);
        assertThat(tasks.detail(root.id(), WORKER).task().canStart()).isTrue();
        blockProcess(running, terminated);
        String reason = terminated ? "所属流程已结束" : "所属流程已挂起";
        Query query =
                new Query(
                        "MINE",
                        "ALL",
                        LocalDate.now(),
                        marker,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        1,
                        100);
        List<Row> rows = new ArrayList<>(tasks.page(query, WORKER).getList());
        rows.addAll(
                tasks.personalTreePage(query, WORKER).getList().stream()
                        .map(PersonalTreeNode::task)
                        .toList());
        rows.addAll(tasks.detail(root.id(), WORKER).nodes());
        // 流程发起人才是任务创建人；模板发布权限不能代替任务查看权限。
        rows.add(tasks.detail(root.id(), INITIATOR).task());
        assertThat(rows)
                .isNotEmpty()
                .allSatisfy(
                        row -> {
                            assertThat(row.canStart()).isFalse();
                            assertThat(row.canExecute()).isFalse();
                            assertThat(row.canEdit()).isFalse();
                            assertThat(row.canPlan()).isFalse();
                            assertThat(row.canClaim()).isFalse();
                            assertThat(row.canAssign()).isFalse();
                            assertThat(row.canAccept()).isFalse();
                            assertThat(row.canDelegate()).isFalse();
                            assertThat(row.canPause()).isFalse();
                            assertThat(row.canResume()).isFalse();
                            assertThat(row.canDelete()).isFalse();
                            assertThat(row.blockedReason()).contains(reason);
                            assertThat(row.plans())
                                    .allSatisfy(plan -> assertThat(plan.canCancel()).isFalse());
                        });
        TaskPlanning.Item schedule = tasks.planContext(contextQuery, WORKER).items().getFirst();
        assertThat(schedule.canArrange()).isFalse();
        assertThat(schedule.canCancel()).isFalse();
        assertThat(schedule.readOnly()).isTrue();
        assertThat(schedule.reason()).contains(reason);
        TaskPlanning.ChecklistItem checklist =
                tasks.checklistContext(contextQuery, WORKER).items().getFirst();
        assertThat(checklist.canAdd()).isFalse();
        assertThat(checklist.reason()).contains(reason);
        assertThat(checklist.todayPlans())
                .hasSize(1)
                .allSatisfy(plan -> assertThat(plan.canCancel()).isFalse());
        if (!terminated) {
            engine.getRuntimeService().activateProcessInstanceById(running.processId());
            Row resumed = tasks.detail(root.id(), WORKER).task();
            assertThat(resumed.canStart()).isTrue();
            assertThat(resumed.canExecute()).isTrue();
            assertThat(resumed.canPlan()).isTrue();
            assertThat(tasks.checklistContext(contextQuery, WORKER).items().getFirst().canAdd())
                    .isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationAtTaskNodeEndsRuntimeThroughPublicService(boolean administrator)
            throws Exception {
        Running running = simpleRun(null);
        Row root = activate(running);
        assertWaiting(running);
        BpmProcessInstanceCancelReqVO command =
                new BpmProcessInstanceCancelReqVO()
                        .setId(running.processId())
                        .setReason("任务节点取消回归");
        BpmProcessInstanceService service = cancellationService();
        if (administrator) service.cancelProcessInstanceByAdmin(PUBLISHER, command);
        else service.cancelProcessInstanceByStartUser(INITIATOR, command);
        retry(running);
        Assertions.assertAll(
                () ->
                        assertThat(
                                        engine.getRuntimeService()
                                                .createProcessInstanceQuery()
                                                .processInstanceId(running.processId())
                                                .singleResult())
                                .as("正式取消入口必须结束没有审批待办的任务等待实例")
                                .isNull(),
                () ->
                        assertThat(
                                        engine.getHistoryService()
                                                .createHistoricProcessInstanceQuery()
                                                .processInstanceId(running.processId())
                                                .singleResult()
                                                .getEndTime())
                                .as("流程取消成功后必须记录真实结束时间")
                                .isNotNull(),
                () ->
                        assertThat(binding(running).getState())
                                .isEqualTo(WorkflowTaskNodes.State.INVALIDATED.name()),
                () ->
                        assertThatThrownBy(() -> transition(root.id(), Action.START, WORKER))
                                .hasMessageContaining("所属流程已结束"));
    }

    @Test
    void parallelActivationAndCompletionRetriesCreateOneGroupAndOneSuccessor() throws Exception {
        Running running = simpleRun(null);
        parallelRetry(running);
        Row root = tasks.detail(binding(running).getTaskId(), WORKER).task();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_instance where root_id=?"
                                        + " and parent_id is null",
                                Long.class,
                                root.id()))
                .isEqualTo(1L);
        transition(root.id(), Action.START, WORKER);
        transition(root.id(), Action.COMPLETE, WORKER);
        parallelRetry(running);
        assertApproval(running);
        assertThatThrownBy(() -> retry(running)).hasMessageContaining("已结束");
        assertThat(
                        engine.getTaskService()
                                .createTaskQuery()
                                .processInstanceId(running.processId())
                                .count())
                .isEqualTo(1L);
    }

    @Test
    void downstreamEngineFailureKeepsCompletedTaskAndRetryableBindingAtomic() throws Exception {
        Running running =
                start(
                        deploy(
                                custom(
                                        node("root", null, marker, WORKER, null),
                                        List.of(),
                                        List.of()),
                                null,
                                true),
                        Map.of("allowNext", false));
        Row root = activate(running);
        transition(root.id(), Action.START, WORKER);
        transition(root.id(), Action.COMPLETE, WORKER);
        retry(running);
        assertWaiting(running);
        assertThat(tasks.detail(root.id(), WORKER).task().status())
                .isEqualTo(State.COMPLETED.name());
        assertThat(binding(running).getLastError()).isNotBlank();
        engine.getRuntimeService().setVariable(running.processId(), "allowNext", true);
        retry(running);
        assertApproval(running);
    }

    @Test
    void taskAndProcessPermissionsRemainSeparateAndForgedWakeupCannotAdvance() throws Exception {
        Running running = simpleRun(null);
        Row root = activate(running);
        assertThatThrownBy(() -> workflow.retry(running.executionId(), STRANGER))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tasks.detail(root.id(), STRANGER))
                .isInstanceOf(RuntimeException.class);
        assertThat(workflow.source(root.id(), WORKER).canViewProcess()).isFalse();
        assertThat(workflow.source(root.id(), INITIATOR).canViewProcess()).isTrue();
        BpmTaskCenterNodeApi api = workflowContext.getBean(BpmTaskCenterNodeApi.class);
        assertThat(
                        api.advance(
                                new BpmTaskCenterNodeExecutionDTO(
                                        running.executionId(),
                                        running.processId(),
                                        "taskNode",
                                        key())))
                .isFalse();
        assertWaiting(running);
    }

    @Test
    void tenantlessHttpArrivalCanBeProcessedAsSchedulerZeroWithoutCrossTenantAccess()
            throws Exception {
        BpmTaskCenterNodeApi api = workflowContext.getBean(BpmTaskCenterNodeApi.class);
        Configuration config =
                custom(node("root", null, marker, WORKER, null), List.of(), List.of());
        try {
            // HTTP 未启用租户时引擎保存空 tenantId，交接表则以 0 表示同一无租户域。
            TenantContextHolder.clear();
            Running tenantless = start(deploy(config, null, false, false, null), Map.of());
            assertThat(
                            engine.getRuntimeService()
                                    .createProcessInstanceQuery()
                                    .processInstanceId(tenantless.processId())
                                    .singleResult()
                                    .getTenantId())
                    .isNullOrEmpty();
            WorkflowTaskNodeDO arrival = bindings.execution(tenantless.executionId(), 0L);
            assertThat(arrival.getState()).isEqualTo(WorkflowTaskNodes.State.CREATING.name());
            assertThat(arrival.getTaskId()).isNull();

            TenantContextHolder.setTenantId(0L);
            retry(tenantless);
            WorkflowTaskNodeDO noTenantBinding = bindings.execution(tenantless.executionId(), 0L);
            assertThat(noTenantBinding.getState())
                    .isEqualTo(WorkflowTaskNodes.State.WAITING.name());
            assertThat(noTenantBinding.getTaskId()).isNotBlank();
            String noTenantRoot = noTenantBinding.getTaskId();
            BpmTaskCenterNodeExecutionDTO noTenantCommand =
                    new BpmTaskCenterNodeExecutionDTO(
                            tenantless.executionId(),
                            tenantless.processId(),
                            "taskNode",
                            noTenantBinding.getId());
            assertThat(api.inspect(noTenantCommand)).isEqualTo(BpmTaskCenterNodeApi.State.WAITING);
            assertThat(workflow.source(noTenantRoot, WORKER).canViewProcess()).isFalse();
            assertThat(workflow.process(tenantless.processId(), INITIATOR)).hasSize(1);
            assertThatThrownBy(() -> workflow.retry(tenantless.executionId(), STRANGER))
                    .isInstanceOf(RuntimeException.class);

            TenantContextHolder.setTenantId(17L);
            assertThat(api.inspect(noTenantCommand)).isEqualTo(BpmTaskCenterNodeApi.State.INACTIVE);
            assertThat(api.advance(noTenantCommand)).isFalse();
            assertThatThrownBy(() -> retry(tenantless)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> workflow.process(tenantless.processId(), PUBLISHER))
                    .isInstanceOf(RuntimeException.class);
            assertThat(workflow.source(noTenantRoot, WORKER)).isNull();
            assertThat(bindings.execution(tenantless.executionId(), 17L)).isNull();

            Running isolated = start(deploy(config, null, false, false, 17L), Map.of());
            retry(isolated);
            WorkflowTaskNodeDO tenantBinding = bindings.execution(isolated.executionId(), 17L);
            assertThat(tenantBinding.getState()).isEqualTo(WorkflowTaskNodes.State.WAITING.name());
            assertThat(tenantBinding.getTaskId()).isNotBlank();
            String isolatedRoot = tenantBinding.getTaskId();
            BpmTaskCenterNodeExecutionDTO isolatedCommand =
                    new BpmTaskCenterNodeExecutionDTO(
                            isolated.executionId(),
                            isolated.processId(),
                            "taskNode",
                            tenantBinding.getId());

            TenantContextHolder.setTenantId(0L);
            assertThat(api.inspect(isolatedCommand)).isEqualTo(BpmTaskCenterNodeApi.State.INACTIVE);
            assertThat(api.advance(isolatedCommand)).isFalse();
            assertThatThrownBy(() -> retry(isolated)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> workflow.process(isolated.processId(), PUBLISHER))
                    .isInstanceOf(RuntimeException.class);
            assertThat(workflow.source(isolatedRoot, WORKER)).isNull();
            assertThat(bindings.execution(isolated.executionId(), 0L)).isNull();
            transition(noTenantRoot, Action.START, WORKER);
            transition(noTenantRoot, Action.COMPLETE, WORKER);
            retry(tenantless);
            assertApproval(tenantless);

            TenantContextHolder.setTenantId(17L);
            transition(isolatedRoot, Action.START, WORKER);
            transition(isolatedRoot, Action.COMPLETE, WORKER);
            retry(isolated);
            assertApproval(isolated);
            assertNoPlans(noTenantRoot);
            assertNoPlans(isolatedRoot);
        } finally {
            TenantContextHolder.setTenantId(TENANT);
        }
    }

    @Test
    void consecutiveTaskNodesCreateDistinctGroupsAndOldBindingCannotWakeSecondNode()
            throws Exception {
        Configuration config =
                custom(node("root", null, marker, WORKER, null), List.of(), List.of());
        Running first = start(deploy(config, null, false, true), Map.of());
        Row firstRoot = activate(first);
        WorkflowTaskNodeDO firstBinding = binding(first);
        transition(firstRoot.id(), Action.START, WORKER);
        transition(firstRoot.id(), Action.COMPLETE, WORKER);
        retry(first);
        String secondExecution =
                engine.getRuntimeService()
                        .createExecutionQuery()
                        .processInstanceId(first.processId())
                        .activityId("secondTaskNode")
                        .singleResult()
                        .getId();
        Running second = new Running(first.processId(), secondExecution);
        Row secondRoot = activate(second);
        assertThat(secondRoot.id()).isNotEqualTo(firstRoot.id());
        assertThat(bindings.process(first.processId(), TENANT)).hasSize(2);
        BpmTaskCenterNodeApi api = workflowContext.getBean(BpmTaskCenterNodeApi.class);
        assertThat(
                        api.advance(
                                new BpmTaskCenterNodeExecutionDTO(
                                        first.executionId(),
                                        first.processId(),
                                        "taskNode",
                                        firstBinding.getId())))
                .isFalse();
        assertThat(
                        engine.getTaskService()
                                .createTaskQuery()
                                .processInstanceId(first.processId())
                                .count())
                .isZero();
        transition(secondRoot.id(), Action.START, WORKER);
        transition(secondRoot.id(), Action.COMPLETE, WORKER);
        retry(second);
        assertApproval(second);
        assertNoPlans(firstRoot.id());
        assertNoPlans(secondRoot.id());
    }

    private Configuration custom(NodeInput root, List<NodeInput> nodes, List<Person> people) {
        return new Configuration(1, Source.CUSTOM, null, null, root, nodes, people, null, null);
    }

    private Row taskNamed(Detail group, String title) {
        return group.nodes().stream()
                .filter(row -> row.title().equals(marker + title))
                .findFirst()
                .orElseThrow();
    }

    private ApplicationRecords.Save materialInput(Map<String, Object> values) {
        return new ApplicationRecords.Save(
                business.resource.applicationId(),
                business.object.objectId(),
                null,
                null,
                values,
                Map.of(),
                Map.of(),
                null,
                business.resource.resourceId(),
                key(),
                null);
    }

    private NodeInput node(String id, String parent, String title, Long owner, Long acceptor) {
        return new NodeInput(
                id,
                parent,
                marker + title,
                null,
                owner,
                null,
                null,
                new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                List.of(),
                null,
                null,
                null,
                AssignmentMode.ASSIGNED,
                List.of(),
                null,
                acceptor);
    }

    private BpmFormDO memberForm() {
        BpmFormDO form = new BpmFormDO();
        form.setId(999991L);
        form.setName("任务节点人员来源");
        form.setConf("{}");
        form.setFields(
                List.of(
                        "{\"type\":\"UserSelect\",\"field\":\"reviewer\",\"props\":{\"multiple\":false}}"));
        return form;
    }

    private Running simpleRun(Long acceptor) throws Exception {
        return start(
                deploy(
                        custom(node("root", null, marker, WORKER, acceptor), List.of(), List.of()),
                        null,
                        false),
                Map.of());
    }

    private Deployed deploy(Configuration configuration, BpmFormDO form, boolean failDownstream)
            throws Exception {
        return deploy(configuration, form, failDownstream, false);
    }

    private Deployed deploy(
            Configuration configuration,
            BpmFormDO form,
            boolean failDownstream,
            boolean consecutive)
            throws Exception {
        return deploy(configuration, form, failDownstream, consecutive, TENANT);
    }

    private Deployed deploy(
            Configuration configuration,
            BpmFormDO form,
            boolean failDownstream,
            boolean consecutive,
            Long tenantId)
            throws Exception {
        String definitionKey = marker + "_" + key().replace("-", "");
        String next = consecutive ? "secondTaskNode" : failDownstream ? "failure" : "approval";
        String failure =
                failDownstream
                        ? """
<serviceTask id="failure" flowable:expression="${allowNext ? 1 : workflowTaskFailure.fail()}"/>
<sequenceFlow id="afterFailure" sourceRef="failure" targetRef="approval"/>
"""
                        : "";
        String second =
                consecutive
                        ? """
<receiveTask id="secondTaskNode" name="第二个任务节点">
  <extensionElements><flowable:nodeType>16</flowable:nodeType>
    <flowable:taskCenterConfig><![CDATA[%s]]></flowable:taskCenterConfig>
  </extensionElements>
</receiveTask>
<sequenceFlow id="afterSecond" sourceRef="secondTaskNode" targetRef="approval"/>
"""
                                .formatted(mapper.writeValueAsString(configuration))
                        : "";
        String xml =
                """
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="workflow-task-integration">
  <process id="%s" isExecutable="true">
    <startEvent id="start"/><sequenceFlow id="a" sourceRef="start" targetRef="taskNode"/>
    <receiveTask id="taskNode" name="工作流任务节点">
      <extensionElements><flowable:nodeType>16</flowable:nodeType>
        <flowable:taskCenterConfig><![CDATA[%s]]></flowable:taskCenterConfig>
      </extensionElements>
    </receiveTask>
    <sequenceFlow id="b" sourceRef="taskNode" targetRef="%s"/>
    %s%s
    <userTask id="approval" name="原审批节点" flowable:assignee="%s"/>
    <sequenceFlow id="c" sourceRef="approval" targetRef="end"/><endEvent id="end"/>
  </process>
</definitions>
"""
                        .formatted(
                                definitionKey,
                                mapper.writeValueAsString(configuration),
                                next,
                                failure,
                                second,
                                REVIEWER);
        BpmTaskCenterNodeModelService.Deployment prepared =
                models.prepare(xml.getBytes(StandardCharsets.UTF_8), null, form, PUBLISHER);
        DeploymentBuilder builder = engine.getRepositoryService().createDeployment();
        if (tenantId != null) builder.tenantId(tenantId.toString());
        Deployment deployment =
                builder.addBytes(definitionKey + ".bpmn20.xml", prepared.bpmn()).deploy();
        deployments.add(deployment.getId());
        String definitionId =
                engine.getRepositoryService()
                        .createProcessDefinitionQuery()
                        .deploymentId(deployment.getId())
                        .singleResult()
                        .getId();
        return new Deployed(definitionId);
    }

    private Running start(Deployed deployed, Map<String, Object> fields) {
        Map<String, Object> variables = new LinkedHashMap<>(fields);
        variables.put(PROCESS_INSTANCE_VARIABLE_START_USER_ID, INITIATOR);
        variables.put(PROCESS_INSTANCE_VARIABLE_STATUS, 1);
        engine.getIdentityService().setAuthenticatedUserId(Long.toString(INITIATOR));
        ProcessInstance process;
        try {
            process =
                    engine.getRuntimeService()
                            .startProcessInstanceById(deployed.definitionId(), variables);
        } finally {
            engine.getIdentityService().setAuthenticatedUserId(null);
        }
        processes.add(process.getId());
        String execution =
                engine.getRuntimeService()
                        .createExecutionQuery()
                        .processInstanceId(process.getId())
                        .activityId("taskNode")
                        .singleResult()
                        .getId();
        return new Running(process.getId(), execution);
    }

    private Row activate(Running running) {
        retry(running);
        WorkflowTaskNodeDO binding = binding(running);
        assertThat(binding.getTaskId()).as("任务创建失败原因：%s", binding.getLastError()).isNotBlank();
        return tasks.detail(binding.getTaskId(), INITIATOR).task();
    }

    private void retry(Running running) {
        workflow.retry(running.executionId(), PUBLISHER);
    }

    private WorkflowTaskNodeDO binding(Running running) {
        return bindings.execution(running.executionId(), TENANT);
    }

    private Row transition(String id, Action action, long actor) {
        Row current = tasks.detail(id, actor).task();
        return tasks.transition(
                        new Transition(id, current.revision(), action, "任务节点专项验证", key()), actor)
                .task();
    }

    private void blockProcess(Running running, boolean terminated) {
        if (terminated) {
            engine.getRuntimeService().deleteProcessInstance(running.processId(), "计划保护回归终止");
            retry(running);
            assertThat(binding(running).getState())
                    .isEqualTo(WorkflowTaskNodes.State.INVALIDATED.name());
        } else engine.getRuntimeService().suspendProcessInstanceById(running.processId());
    }

    /** 旧页面已持有合法计划参数，来源失效后仍须由写入口拒绝且不产生审计或修订变化。 */
    private void assertPlanningWriteRejected(String rootId, boolean terminated, Runnable write) {
        List<Map<String, Object>> plans =
                jdbc.queryForList(
                        "select * from public.nocode_task_plan where task_id=? order by id",
                        rootId);
        Map<String, Object> revisions =
                jdbc.queryForMap(
                        "select lock_version,schedule_version from public.nocode_task_instance"
                                + " where id=?",
                        rootId);
        Long events =
                jdbc.queryForObject(
                        "select count(*) from public.nocode_task_event where root_id=?",
                        Long.class,
                        rootId);
        Assertions.assertAll(
                () ->
                        assertThatThrownBy(write::run)
                                .hasMessageContaining(terminated ? "所属流程已结束" : "所属流程已挂起"),
                () ->
                        assertThat(
                                        jdbc.queryForList(
                                                "select * from public.nocode_task_plan where"
                                                        + " task_id=? order by id",
                                                rootId))
                                .isEqualTo(plans),
                () ->
                        assertThat(
                                        jdbc.queryForMap(
                                                "select lock_version,schedule_version from"
                                                        + " public.nocode_task_instance where id=?",
                                                rootId))
                                .isEqualTo(revisions),
                () ->
                        assertThat(
                                        jdbc.queryForObject(
                                                "select count(*) from public.nocode_task_event"
                                                        + " where root_id=?",
                                                Long.class,
                                                rootId))
                                .isEqualTo(events));
    }

    /** 复用现有生命周期夹具的事务装配，调用正式取消服务与真实结束逻辑，不以直接删引擎代替。 */
    private BpmProcessInstanceService cancellationService() {
        BpmProcessDefinitionService definitions =
                workflowContext.getBean(BpmProcessDefinitionService.class);
        BpmProcessDefinitionInfoDO definition = new BpmProcessDefinitionInfoDO();
        definition.setAllowCancelRunningProcess(true);
        when(definitions.getProcessDefinitionInfo(anyString())).thenReturn(definition);
        BpmProcessInstanceServiceImpl service = new BpmProcessInstanceServiceImpl();
        ReflectionTestUtils.setField(service, "runtimeService", engine.getRuntimeService());
        ReflectionTestUtils.setField(
                service, "taskService", workflowContext.getBean(BpmTaskService.class));
        ReflectionTestUtils.setField(service, "processDefinitionService", definitions);
        ReflectionTestUtils.setField(
                service, "adminUserApi", workflowContext.getBean(AdminUserApi.class));
        ProxyFactory proxy = new ProxyFactory(service);
        proxy.addAdvice(
                new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (BpmProcessInstanceService) proxy.getProxy();
    }

    private void assertWaiting(Running running) {
        assertThat(
                        engine.getRuntimeService()
                                .createExecutionQuery()
                                .executionId(running.executionId())
                                .activityId("taskNode")
                                .count())
                .isEqualTo(1L);
        assertThat(
                        engine.getTaskService()
                                .createTaskQuery()
                                .processInstanceId(running.processId())
                                .count())
                .isZero();
    }

    private Task assertApproval(Running running) {
        List<Task> approvals =
                engine.getTaskService()
                        .createTaskQuery()
                        .processInstanceId(running.processId())
                        .list();
        assertThat(approvals).hasSize(1);
        assertThat(approvals.getFirst().getTaskDefinitionKey()).isEqualTo("approval");
        return approvals.getFirst();
    }

    private void assertNoPlans(String rootId) {
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_plan where task_id in"
                                        + " (select id from public.nocode_task_instance where"
                                        + " root_id=?)",
                                Long.class,
                                rootId))
                .isZero();
    }

    private long countTasks() {
        return jdbc.queryForObject(
                "select count(*) from public.nocode_task_instance where title like ?",
                Long.class,
                marker + "%");
    }

    private void parallelRetry(Running running) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                futures.add(
                        workers.submit(
                                () -> {
                                    TenantContextHolder.setTenantId(TENANT);
                                    try {
                                        ready.countDown();
                                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                                        try {
                                            retry(running);
                                        } catch (
                                                com.lingan.ucp.framework.common.exception
                                                                .ServiceException
                                                        failure) {
                                            // 第二个请求到达时若已交接完毕，公开重试入口拒绝已结束节点也是幂等结果。
                                            assertThat(binding(running).getState())
                                                    .isEqualTo(
                                                            WorkflowTaskNodes.State.COMPLETED
                                                                    .name());
                                            assertThat(failure.getMessage()).contains("已结束");
                                        }
                                    } catch (InterruptedException ex) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(ex);
                                    } finally {
                                        TenantContextHolder.clear();
                                    }
                                }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
    }

    private static AdminUserRespDTO activeUser(long id) {
        AdminUserRespDTO user = new AdminUserRespDTO();
        user.setId(id);
        user.setNickname("任务节点专项人员" + id);
        user.setStatus(0);
        return user;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private record Deployed(String definitionId) {}

    private record Running(String processId, String executionId) {}

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @Import({
        // 查询夹具继承完成链路装配，并加载正式已办分页所需的 Flowable Mapper。
        BpmTaskQueryIntegrationTest.Fixture.class,
        BpmTaskCenterNodeModelServiceImpl.class,
        BpmTaskCenterNodeListener.class,
        BpmTaskCenterNodeServiceImpl.class,
        BpmTaskCenterNodeApiImpl.class,
        WorkflowTaskNodeHandler.class,
        WorkflowTaskNodeServiceImpl.class
    })
    static class Fixture {
        @Bean
        WorkflowTaskNodeMapper workflowTaskNodeMapper() {
            return session.getMapper(WorkflowTaskNodeMapper.class);
        }

        @Bean
        BpmProcessInstanceCopyMapper processInstanceCopyMapper() {
            return mock(BpmProcessInstanceCopyMapper.class);
        }

        @Bean
        WorkflowTaskFailure workflowTaskFailure() {
            return new WorkflowTaskFailure();
        }
    }

    /** 制造后继引擎失败，验证完成推进与交接状态仍在同一真实事务中回滚。 */
    public static class WorkflowTaskFailure {
        public int fail() {
            throw new IllegalStateException("workflow-task-downstream-failure");
        }
    }
}
