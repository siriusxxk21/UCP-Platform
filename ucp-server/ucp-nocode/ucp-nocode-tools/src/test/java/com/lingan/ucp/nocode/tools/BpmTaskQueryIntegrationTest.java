package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.module.bpm.framework.flowable.core.enums.BpmnModelConstants.START_USER_NODE_ID;
import static com.lingan.ucp.module.bpm.framework.flowable.core.enums.BpmnVariableConstants.TASK_VARIABLE_STATUS;

import static org.assertj.core.api.Assertions.assertThat;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.common.util.date.DateUtils;
import com.lingan.ucp.framework.tenant.core.context.TenantContextHolder;
import com.lingan.ucp.module.bpm.controller.admin.task.vo.task.BpmTaskPageReqVO;
import com.lingan.ucp.module.bpm.enums.task.BpmTaskStatusEnum;
import com.lingan.ucp.module.bpm.framework.flowable.config.BpmFlowableConfiguration;
import com.lingan.ucp.module.bpm.framework.flowable.core.listener.BpmTaskCompletionGuardListener;
import com.lingan.ucp.module.bpm.service.task.BpmTaskService;

import jakarta.validation.Validation;

import org.flowable.common.engine.impl.persistence.StrongUuidGenerator;
import org.flowable.engine.ProcessEngine;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.*;

import javax.sql.DataSource;

/**
 * 当前开发库的真实 Flowable 查询回归：验证筛选发生于计数、分页之前。
 *
 * <p>数据库通过正式启动模块配置装配；只创建并删除本次 UUID 标识的部署和任务。
 */
class BpmTaskQueryIntegrationTest {

    private static final long ACTOR = 10001L;
    private static final long OTHER_ACTOR = 20002L;
    private static final long TENANT = 910091L;
    private static final long OTHER_TENANT = 910092L;
    private static final LocalDateTime START = LocalDateTime.of(2020, 1, 10, 10, 0);
    private static final LocalDateTime END = START.plusHours(2);

    static ConfigurableApplicationContext tool;
    static AnnotationConfigApplicationContext context;
    static ProcessEngine engine;
    static BpmTaskService service;
    final List<String> deployments = new ArrayList<>();
    final List<String> standaloneTasks = new ArrayList<>();
    String prefix;

    @BeforeAll
    static void open() {
        tool = NocodeToolContext.open();
        context = new AnnotationConfigApplicationContext();
        context.setParent(tool);
        context.register(Fixture.class);
        context.refresh();
        engine = context.getBean(ProcessEngine.class);
        service = context.getBean(BpmTaskService.class);
    }

    @AfterAll
    static void close() {
        if (context != null) context.close();
        if (tool != null) tool.close();
    }

    @BeforeEach
    void setup() {
        prefix = "query_" + UUID.randomUUID().toString().replace("-", "");
        TenantContextHolder.setTenantId(TENANT);
    }

    @AfterEach
    void clean() {
        try {
            for (String deployment : deployments) {
                engine.getRepositoryService().deleteDeployment(deployment, true);
            }
            for (String task : standaloneTasks) {
                engine.getTaskService().deleteTask(task, true);
            }
        } finally {
            engine.getProcessEngineConfiguration().getClock().reset();
            TenantContextHolder.clear();
        }
    }

    @Test
    void flowable72CombinesNativeTimeBoundsWithAnd() {
        String model = deploy("primary", TENANT, "approve");
        start(
                model,
                TENANT,
                START.minusSeconds(1),
                ACTOR,
                "finance",
                BpmTaskStatusEnum.APPROVE,
                true);
        Task middle =
                start(
                        model,
                        TENANT,
                        START.plusHours(1),
                        ACTOR,
                        "finance",
                        BpmTaskStatusEnum.APPROVE,
                        true);
        start(model, TENANT, END.plusSeconds(1), ACTOR, "finance", BpmTaskStatusEnum.APPROVE, true);

        var query =
                engine.getHistoryService()
                        .createHistoricTaskInstanceQuery()
                        .taskTenantId(Long.toString(TENANT))
                        .processDefinitionKey(model)
                        .taskCreatedAfter(DateUtils.of(START))
                        .taskCreatedBefore(DateUtils.of(END));
        assertThat(query.count()).isEqualTo(1);
        assertThat(query.listPage(0, 1))
                .extracting(HistoricTaskInstance::getId)
                .containsExactly(middle.getId());
        assertThat(query.listPage(1, 1)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void timeRangeIncludesBothBoundsAndKeepsTotalsAcrossPages(boolean done) {
        String model = deploy("primary", TENANT, "approve");
        Task lower = start(model, TENANT, START, ACTOR, "finance", BpmTaskStatusEnum.APPROVE, true);
        Task middle =
                start(
                        model,
                        TENANT,
                        START.plusHours(1),
                        ACTOR,
                        "finance",
                        BpmTaskStatusEnum.APPROVE,
                        true);
        Task upper = start(model, TENANT, END, ACTOR, "finance", BpmTaskStatusEnum.APPROVE, true);
        // 混合区间内外记录跨页，旧版分页后过滤会使页内数量和 total 不一致。
        start(
                model,
                TENANT,
                START.minusSeconds(1),
                ACTOR,
                "finance",
                BpmTaskStatusEnum.APPROVE,
                true);
        start(model, TENANT, END.plusSeconds(1), ACTOR, "finance", BpmTaskStatusEnum.APPROVE, true);
        var request = request();
        request.setCreateTime(new LocalDateTime[] {START, END});
        assertPages(done, request, List.of(lower.getId(), middle.getId(), upper.getId()));
        request.setPageNo(1);
        request.setCreateTime(new LocalDateTime[] {START, START});
        assertThat(page(done, request).getList())
                .extracting(HistoricTaskInstance::getId)
                .containsExactly(lower.getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void categoryDefinitionStatusTenantAndAssigneeAreAppliedBeforePaging(boolean done) {
        String model = deploy("primary", TENANT, "approve");
        String otherModel = deploy("other", TENANT, "approve");
        deploy("primary", OTHER_TENANT, "approve");
        Task first = start(model, TENANT, START, ACTOR, "finance", BpmTaskStatusEnum.APPROVE, true);
        Task second =
                start(model, TENANT, START, ACTOR, "finance", BpmTaskStatusEnum.APPROVE, true);
        start(model, TENANT, START, ACTOR, "hr", BpmTaskStatusEnum.APPROVE, true);
        start(otherModel, TENANT, START, ACTOR, "finance", BpmTaskStatusEnum.APPROVE, true);
        start(model, TENANT, START, ACTOR, "finance", BpmTaskStatusEnum.REJECT, true);
        start(model, OTHER_TENANT, START, ACTOR, "finance", BpmTaskStatusEnum.APPROVE, true);
        Task otherActor =
                start(
                        model,
                        TENANT,
                        START,
                        OTHER_ACTOR,
                        "finance",
                        BpmTaskStatusEnum.APPROVE,
                        true);
        var request = request();
        request.setCategory("finance");
        request.setProcessDefinitionKey(model);
        request.setStatus(BpmTaskStatusEnum.APPROVE.getStatus());
        var expected = new ArrayList<>(List.of(first.getId(), second.getId()));
        if (!done) expected.add(otherActor.getId());
        assertPages(done, request, expected);
        request.setPageNo(1);
        request.setCategory(null);
        assertThat(page(done, request).getTotal()).isEqualTo(expected.size() + 1L);
        request.setCategory("finance");
        request.setProcessDefinitionKey(null);
        assertThat(page(done, request).getTotal()).isEqualTo(expected.size() + 1L);
        request.setProcessDefinitionKey(model);
        request.setStatus(null);
        assertThat(page(done, request).getTotal()).isEqualTo(expected.size() + 1L);
        request.setStatus(BpmTaskStatusEnum.APPROVE.getStatus());
        TenantContextHolder.setTenantId(OTHER_TENANT);
        assertThat(page(done, request).getTotal()).isEqualTo(1);
    }

    @Test
    void excludesStartUserBeforeCountingAndPagingAndPreservesNullNodeTasks() {
        String model = deploy("primary", TENANT, "approve");
        String startModel = deploy("automatic", TENANT, START_USER_NODE_ID);
        Task first = start(model, TENANT, START, ACTOR, "finance", BpmTaskStatusEnum.APPROVE, true);
        Task second =
                start(model, TENANT, START, ACTOR, "finance", BpmTaskStatusEnum.APPROVE, true);
        Task third = start(model, TENANT, START, ACTOR, "finance", BpmTaskStatusEnum.APPROVE, true);
        for (int index = 0; index < 5; index++) {
            start(
                    startModel,
                    TENANT,
                    END.plusMinutes(index),
                    ACTOR,
                    "finance",
                    BpmTaskStatusEnum.APPROVE,
                    true);
        }
        assertPages(true, request(), List.of(first.getId(), second.getId(), third.getId()));
        assertThat(page(false, request()).getTotal()).isEqualTo(8);

        Task standalone = engine.getTaskService().newTask();
        standalone.setName(prefix);
        standalone.setTenantId(Long.toString(TENANT));
        standalone.setAssignee(Long.toString(ACTOR));
        engine.getTaskService().saveTask(standalone);
        standaloneTasks.add(standalone.getId());
        engine.getTaskService()
                .setVariablesLocal(
                        standalone.getId(),
                        Map.of(
                                TASK_VARIABLE_STATUS,
                                BpmTaskStatusEnum.APPROVE.getStatus(),
                                "query_payload",
                                "独立任务"));
        engine.getTaskService().complete(standalone.getId());
        assertPages(
                true,
                request(),
                List.of(first.getId(), second.getId(), third.getId(), standalone.getId()));
    }

    @Test
    void managerCanFilterRunningStatusWhileDoneOnlyContainsFinishedTasks() {
        String model = deploy("primary", TENANT, "approve");
        Task running =
                start(model, TENANT, START, ACTOR, "finance", BpmTaskStatusEnum.RUNNING, false);
        start(model, TENANT, START, ACTOR, "finance", BpmTaskStatusEnum.APPROVE, true);
        var request = request();
        request.setStatus(BpmTaskStatusEnum.RUNNING.getStatus());
        assertThat(page(true, request).getTotal()).isZero();
        assertThat(page(false, request).getTotal()).isEqualTo(1);
        assertThat(page(false, request).getList())
                .extracting(HistoricTaskInstance::getId)
                .containsExactly(running.getId());
    }

    @Test
    void validatesIncompleteNullAndReversedTimeRanges() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var request = request();
            assertThat(validator.validate(request)).isEmpty();
            for (LocalDateTime[] invalid :
                    List.of(
                            new LocalDateTime[] {START},
                            new LocalDateTime[] {START, null},
                            new LocalDateTime[] {null, END},
                            new LocalDateTime[] {END, START},
                            new LocalDateTime[] {START, END, END})) {
                request.setCreateTime(invalid);
                assertThat(validator.validate(request))
                        .extracting(v -> v.getPropertyPath().toString())
                        .contains("validCreateTimeRange");
            }
            request.setCreateTime(new LocalDateTime[] {START, END});
            assertThat(validator.validate(request)).isEmpty();
        }
    }

    private void assertPages(boolean done, BpmTaskPageReqVO request, List<String> expected) {
        List<String> actual = new ArrayList<>();
        for (int index = 1; index <= (expected.size() + 1) / 2 + 1; index++) {
            request.setPageNo(index);
            var page = page(done, request);
            assertThat(page.getTotal()).isEqualTo(expected.size());
            int remaining = Math.max(expected.size() - (index - 1) * 2, 0);
            assertThat(page.getList()).hasSize(Math.min(2, remaining));
            actual.addAll(page.getList().stream().map(HistoricTaskInstance::getId).toList());
            for (var task : page.getList()) {
                assertThat(task.getTaskLocalVariables())
                        .containsKeys(TASK_VARIABLE_STATUS, "query_payload");
            }
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(actual).doesNotHaveDuplicates();
    }

    private PageResult<HistoricTaskInstance> page(boolean done, BpmTaskPageReqVO request) {
        return done ? service.getTaskDonePage(ACTOR, request) : service.getTaskPage(ACTOR, request);
    }

    private BpmTaskPageReqVO request() {
        var request = new BpmTaskPageReqVO();
        request.setName(prefix);
        request.setPageNo(1);
        request.setPageSize(2);
        return request;
    }

    private String deploy(String suffix, long tenant, String node) {
        String key = prefix + "_" + suffix;
        String xml =
                """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="query_regression">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/><sequenceFlow id="a" sourceRef="start" targetRef="%s"/>
                    <userTask id="%s" name="%s" flowable:assignee="${actor}"/>
                    <sequenceFlow id="b" sourceRef="%s" targetRef="end"/><endEvent id="end"/>
                  </process>
                </definitions>
                """
                        .formatted(key, node, node, prefix, node);
        var deployment =
                engine.getRepositoryService()
                        .createDeployment()
                        .tenantId(Long.toString(tenant))
                        .name(key)
                        .addString(key + ".bpmn20.xml", xml)
                        .deploy();
        deployments.add(deployment.getId());
        return key;
    }

    private Task start(
            String model,
            long tenant,
            LocalDateTime created,
            long actor,
            String category,
            BpmTaskStatusEnum status,
            boolean finished) {
        engine.getProcessEngineConfiguration().getClock().setCurrentTime(DateUtils.of(created));
        var instance =
                engine.getRuntimeService()
                        .startProcessInstanceByKeyAndTenantId(
                                model,
                                Map.of("actor", Long.toString(actor)),
                                Long.toString(tenant));
        Task task =
                engine.getTaskService()
                        .createTaskQuery()
                        .processInstanceId(instance.getId())
                        .singleResult();
        task.setCategory(category);
        engine.getTaskService().saveTask(task);
        engine.getTaskService()
                .setVariablesLocal(
                        task.getId(),
                        Map.of(
                                TASK_VARIABLE_STATUS,
                                status.getStatus(),
                                "query_payload",
                                "变量联表分页回归"));
        if (finished) {
            engine.getProcessEngineConfiguration()
                    .getClock()
                    .setCurrentTime(DateUtils.of(END.plusDays(1)));
            engine.getTaskService().complete(task.getId());
        }
        return task;
    }

    @Configuration(proxyBeanMethods = false)
    static class Fixture extends BpmTaskCompletionIntegrationTest.Fixture {

        @Override
        @Bean(destroyMethod = "close")
        ProcessEngine processEngine(
                DataSource ds,
                PlatformTransactionManager manager,
                BpmTaskCompletionGuardListener listener,
                ApplicationContext applicationContext) {
            var configuration = new SpringProcessEngineConfiguration();
            configuration.setApplicationContext(applicationContext);
            configuration.setDataSource(ds);
            configuration.setTransactionManager(manager);
            configuration.setDatabaseSchemaUpdate("false");
            configuration.setAsyncExecutorActivate(false);
            configuration.setDisableIdmEngine(true);
            configuration.setDisableEventRegistry(true);
            configuration.setEnableConfiguratorServiceLoader(false);
            configuration.setIdGenerator(new StrongUuidGenerator());
            configuration.setEventListeners(List.of(listener));
            new BpmFlowableConfiguration().bpmTaskQueryConfigurer().configure(configuration);
            return configuration.buildProcessEngine();
        }
    }
}
