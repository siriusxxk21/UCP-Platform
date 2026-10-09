package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.richuang.os.module.bpm.api.task.*;
import com.richuang.os.module.bpm.api.task.BpmProcessTaskApiImpl;
import com.richuang.os.module.bpm.api.task.dto.*;
import com.richuang.os.module.bpm.controller.admin.task.vo.task.BpmTaskApproveReqVO;
import com.richuang.os.module.bpm.framework.flowable.core.listener.BpmTaskCompletionGuardListener;
import com.richuang.os.module.bpm.service.definition.*;
import com.richuang.os.module.bpm.service.message.BpmMessageService;
import com.richuang.os.module.bpm.service.task.*;
import com.richuang.os.module.system.api.dept.DeptApi;
import com.richuang.os.module.system.api.user.AdminUserApi;

import org.flowable.common.engine.impl.persistence.StrongUuidGenerator;
import org.flowable.engine.*;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import javax.sql.DataSource;

/**
 * 当前开发库的真实 Flowable 完成／事务回归。只部署本次 UUID 标识的模型并清理自身材料。
 *
 * <p>来源 Guard 为测试实现，材料由 JDBC 夹具写入；不把此测试称为无代码来源办理或真实角色登录验收。
 */
class BpmTaskCompletionIntegrationTest {
    static ConfigurableApplicationContext tool;
    static AnnotationConfigApplicationContext context;
    static ProcessEngine engine;
    static BpmProcessTaskApi api;
    static JdbcTemplate jdbc;
    static TransactionTemplate transaction;
    static TestGuard guard;
    final List<String> deployments = new ArrayList<>();
    final List<String> materials = new ArrayList<>();

    @BeforeAll
    static void open() {
        tool = NocodeToolContext.open();
        context = new AnnotationConfigApplicationContext();
        context.setParent(tool);
        context.register(Fixture.class);
        context.refresh();
        engine = context.getBean(ProcessEngine.class);
        api = context.getBean(BpmProcessTaskApi.class);
        jdbc = tool.getBean(JdbcTemplate.class);
        transaction = new TransactionTemplate(tool.getBean(PlatformTransactionManager.class));
        guard = context.getBean(TestGuard.class);
        when(context.getBean(BpmModelService.class).getBpmnModelByDefinitionId(anyString()))
                .thenAnswer(i -> engine.getRepositoryService().getBpmnModel(i.getArgument(0)));
        when(context.getBean(BpmProcessInstanceService.class).getProcessInstance(anyString()))
                .thenAnswer(
                        i ->
                                engine.getRuntimeService()
                                        .createProcessInstanceQuery()
                                        .processInstanceId(i.getArgument(0))
                                        .includeProcessVariables()
                                        .singleResult());
    }

    @AfterAll
    static void close() {
        if (context != null) context.close();
        if (tool != null) tool.close();
    }

    @AfterEach
    void clean() {
        guard.nestedCompletion = null;
        context.getDefaultListableBeanFactory().destroySingleton("duplicateGuard");
        for (var deployment : deployments)
            engine.getRepositoryService().deleteDeployment(deployment, true);
        for (var id : materials) {
            jdbc.update("DELETE FROM public.nocode_work_submission WHERE id=?", id);
            jdbc.update("DELETE FROM public.nocode_work_draft WHERE id=?", id);
        }
    }

    @Test
    void independentFormSnapshotSurvivesDeploymentAndApprovalValidatesBeforeCompletion() {
        var form = new com.richuang.os.module.bpm.dal.dataobject.definition.BpmFormDO();
        form.setId(99999L);
        form.setName("节点发布快照");
        form.setConf("{}");
        form.setFields(
                List.of(
                        "{\"type\":\"input\",\"field\":\"answer\",\"validate\":[{\"required\":true}]}"));
        var forms = mock(BpmFormService.class);
        when(forms.getForm(99999L)).thenReturn(form);
        var resolver = new BpmNodeFormServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(resolver, "formService", forms);
        var metadata =
                new com.richuang.os.module.bpm.controller.admin.definition.vo.model
                        .BpmModelMetaInfoVO();
        metadata.setFormType(10);
        String key = "fd_form_" + UUID.randomUUID().toString().replace("-", "");
        String xml =
                """
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn"
 xmlns:node="https://richuang.com/schema/bpmn/node-form" targetNamespace="fd">
 <process id="%s" isExecutable="true">
  <startEvent id="start"/><sequenceFlow id="a" sourceRef="start" targetRef="work"/>
  <userTask id="work" name="独立表单" flowable:assignee="10001"
   node:configuration="{&quot;mode&quot;:&quot;OVERRIDE&quot;,&quot;source&quot;:{&quot;kind&quot;:&quot;FLOW_FORM&quot;,&quot;formId&quot;:&quot;99999&quot;}}"/>
  <sequenceFlow id="b" sourceRef="work" targetRef="end"/><endEvent id="end"/>
 </process>
</definitions>
"""
                        .formatted(key);
        byte[] resolved =
                resolver.resolveForDeployment(
                        xml.getBytes(java.nio.charset.StandardCharsets.UTF_8), metadata);
        var deployment =
                engine.getRepositoryService()
                        .createDeployment()
                        .addBytes(key + ".bpmn20.xml", resolved)
                        .deploy();
        deployments.add(deployment.getId());
        form.setName("发布后被修改");
        form.setFields(List.of());
        var instance = engine.getRuntimeService().startProcessInstanceByKey(key);
        var task =
                engine.getTaskService()
                        .createTaskQuery()
                        .processInstanceId(instance.getId())
                        .singleResult();
        when(context.getBean(BpmProcessDefinitionService.class)
                        .getProcessDefinitionBpmnModel(anyString()))
                .thenAnswer(i -> engine.getRepositoryService().getBpmnModel(i.getArgument(0)));
        var taskService = context.getBean(BpmTaskService.class);
        var todo = taskService.getTodoTask(10001L, task.getId(), instance.getId());
        assertThat(todo.getFormName()).isEqualTo("节点发布快照");
        assertThat(todo.getFormFields()).hasSize(1);
        assertThat(todo.getFormBinding().path("mode").asText()).isEqualTo("OVERRIDE");
        var request = new BpmTaskApproveReqVO();
        request.setId(task.getId());
        request.setReason("独立表单验收");
        request.setVariables(Map.of());
        assertThatThrownBy(() -> taskService.approveTask(10001L, request))
                .hasMessageContaining("必填");
        assertThat(current(task)).isNotNull();
        assertThat(engine.getTaskService().getTaskComments(task.getId())).isEmpty();
        request.setVariables(Map.of("answer", "已填写", "unconfigured", "不能提交"));
        assertThatThrownBy(() -> taskService.approveTask(10001L, request))
                .hasMessageContaining("不可写");
        request.setVariables(Map.of("answer", "已填写"));
        taskService.approveTask(10001L, request);
        assertThat(current(task)).isNull();
        var history = taskService.getHistoricTask(task.getId());
        assertThat(
                        com.richuang.os.module.bpm.framework.flowable.core.util.FlowableUtils
                                .getTaskFormVariable(history))
                .containsEntry("answer", "已填写");
    }

    @Test
    void legacyTasksContinueThroughTheOriginalApprovalService() {
        var task = start(null, false);
        var request = new BpmTaskApproveReqVO();
        request.setId(task.getId());
        request.setReason("兼容审批");
        context.getBean(BpmTaskService.class).approveTask(10001L, request);
        assertThat(current(task)).isNull();
        assertThat(
                        engine.getHistoryService()
                                .createHistoricTaskInstanceQuery()
                                .taskId(task.getId())
                                .finished()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void originalApprovalCannotCompleteManagedTasksOrLeaveComments() {
        var task = start("test_material", false);
        var request = new BpmTaskApproveReqVO();
        request.setId(task.getId());
        request.setReason("绕过业务提交");
        assertThatThrownBy(() -> context.getBean(BpmTaskService.class).approveTask(10001L, request))
                .hasMessageContaining("业务办理入口");
        assertThat(current(task)).isNotNull();
        assertThat(engine.getTaskService().getTaskComments(task.getId())).isEmpty();
    }

    @Test
    void directEngineCompletionCannotForgeProofUsingVariables() {
        var task = start("test_material", false);
        assertThatThrownBy(
                        () ->
                                engine.getTaskService()
                                        .complete(
                                                task.getId(),
                                                Map.of(
                                                        "handler",
                                                        "",
                                                        "submissionId",
                                                        "forged",
                                                        "taskAuthorized",
                                                        true)))
                .hasMessageContaining("业务办理入口");
        assertThat(current(task)).isNotNull();
        assertThat(
                        engine.getRuntimeService()
                                .getVariable(task.getProcessInstanceId(), "submissionId"))
                .isNull();
    }

    @Test
    void taskApiChecksAssignmentAndSuspension() {
        var task = start("test_material", false);
        var view = api.getAssignedTask(10001L, task.getId());
        assertThat(view.processDefinitionId()).isEqualTo(task.getProcessDefinitionId());
        assertThat(view.handler()).isEqualTo("test_material");
        assertThatThrownBy(() -> api.getAssignedTask(20002L, task.getId()))
                .hasMessageContaining("不是你");
        assertThatThrownBy(() -> api.completeBusinessTask(20002L, command(task, "forged")))
                .hasMessageContaining("不是你");
        engine.getRuntimeService().suspendProcessInstanceById(task.getProcessInstanceId());
        assertThatThrownBy(() -> api.getAssignedTask(10001L, task.getId()))
                .hasMessageContaining("挂起");
    }

    @Test
    void absentAndDuplicateGuardsFailClosed() {
        var unknown = start("unregistered", false);
        assertThatThrownBy(() -> api.completeBusinessTask(10001L, command(unknown, "missing")))
                .hasMessageContaining("未正确装配");
        var task = start("test_material", false);
        context.getDefaultListableBeanFactory()
                .registerSingleton("duplicateGuard", new TestGuard(jdbc));
        assertThatThrownBy(() -> api.completeBusinessTask(10001L, command(task, "missing")))
                .hasMessageContaining("未正确装配");
        assertThat(current(task)).isNotNull();
    }

    @Test
    void rejectedMaterialRollsBackSameTransactionWrites() {
        var task = start("test_material", false);
        var id = newMaterialId();
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            stageMaterial(id, "different-task", 10001L);
                                            api.completeBusinessTask(10001L, command(task, id));
                                        }))
                .hasMessageContaining("材料与当前任务不匹配");
        assertRolledBack(task, id);
    }

    @Test
    void validMaterialAndTaskCompletionCommitTogether() {
        var task = start("test_material", false);
        var id = newMaterialId();
        transaction.executeWithoutResult(
                status -> {
                    stageMaterial(id, task.getId(), 10001L);
                    api.completeBusinessTask(10001L, command(task, id));
                });
        assertThat(current(task)).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_work_submission WHERE id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(
                        engine.getHistoryService()
                                .createHistoricTaskInstanceQuery()
                                .taskId(task.getId())
                                .finished()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void downstreamEngineFailureRollsBackMaterialAndAllowsFreshRetry() {
        var task = start("test_material", true);
        var id = newMaterialId();
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            stageMaterial(id, task.getId(), 10001L);
                                            api.completeBusinessTask(10001L, command(task, id));
                                        }))
                .isInstanceOf(RuntimeException.class);
        assertRolledBack(task, id);
        // 异常退出后证明必须清除；同线程直接重试不能继承上次材料。
        assertThatThrownBy(() -> engine.getTaskService().complete(task.getId()))
                .hasMessageContaining("业务办理入口");
        engine.getRuntimeService().setVariable(task.getProcessInstanceId(), "allowNext", true);
        transaction.executeWithoutResult(
                status -> {
                    stageMaterial(id, task.getId(), 10001L);
                    api.completeBusinessTask(10001L, command(task, id));
                });
        assertThat(current(task)).isNull();
    }

    @Test
    void completionScopeCannotAuthorizeAnotherTask() {
        var first = start("test_material", false);
        var other = start("test_material", false);
        var id = newMaterialId();
        guard.nestedCompletion = () -> engine.getTaskService().complete(other.getId());
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            stageMaterial(id, first.getId(), 10001L);
                                            api.completeBusinessTask(10001L, command(first, id));
                                        }))
                .hasMessageContaining("业务办理入口");
        assertRolledBack(first, id);
        assertThat(current(other)).isNotNull();
    }

    @Test
    void completionProofCannotEscapeIntoRequiresNewTransaction() {
        var task = start("test_material", false);
        var id = newMaterialId();
        var isolated = new TransactionTemplate(tool.getBean(PlatformTransactionManager.class));
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        guard.nestedCompletion =
                () ->
                        isolated.executeWithoutResult(
                                status -> engine.getTaskService().complete(task.getId()));
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            stageMaterial(id, task.getId(), 10001L);
                                            api.completeBusinessTask(10001L, command(task, id));
                                        }))
                .hasMessageContaining("业务办理入口");
        assertRolledBack(task, id);
    }

    @Test
    void invalidDeployedBindingCannotBeDowngradedToLegacy() {
        var task = start("INVALID", false);
        assertThatThrownBy(() -> engine.getTaskService().complete(task.getId()))
                .hasMessageContaining("业务绑定无效");
        assertThat(current(task)).isNotNull();
    }

    private Task start(String handler, boolean failingNext) {
        String key = "ft07_" + UUID.randomUUID().toString().replace("-", "");
        String binding = handler == null ? "" : " os:handler=\"" + handler + "\"";
        String next =
                failingNext
                        ? "<serviceTask id=\"next\" flowable:expression=\"${allowNext ? 1 :"
                                + " missingBean.fail()}\"/>"
                        : "<serviceTask id=\"next\" flowable:expression=\"${1}\"/>";
        String xml =
                """
<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:flowable="http://flowable.org/bpmn" xmlns:os="%s" targetNamespace="ft07">
  <process id="%s" isExecutable="true">
    <startEvent id="start"/><sequenceFlow id="a" sourceRef="start" targetRef="work"/>
    <userTask id="work" name="FT07 完成校验" flowable:assignee="10001"%s/>
    <sequenceFlow id="b" sourceRef="work" targetRef="next"/>%s
    <sequenceFlow id="c" sourceRef="next" targetRef="end"/><endEvent id="end"/>
  </process>
</definitions>
"""
                        .formatted(BpmBusinessTaskBinding.NAMESPACE, key, binding, next);
        var deployment =
                engine.getRepositoryService()
                        .createDeployment()
                        .name(key)
                        .addString(key + ".bpmn20.xml", xml)
                        .deploy();
        deployments.add(deployment.getId());
        var instance =
                engine.getRuntimeService()
                        .startProcessInstanceByKey(key, Map.of("allowNext", false));
        return engine.getTaskService()
                .createTaskQuery()
                .processInstanceId(instance.getId())
                .singleResult();
    }

    private BpmBusinessTaskCompleteReqDTO command(Task task, String id) {
        return new BpmBusinessTaskCompleteReqDTO(task.getId(), id, "FT07 校验提交", Map.of());
    }

    private Task current(Task task) {
        return engine.getTaskService().createTaskQuery().taskId(task.getId()).singleResult();
    }

    private String newMaterialId() {
        String id = UUID.randomUUID().toString();
        materials.add(id);
        return id;
    }

    private void stageMaterial(String id, String taskId, long actor) {
        jdbc.update(
                "INSERT INTO public.nocode_work_draft"
                    + " (id,source_type,source_id,resource_json,object_id,values_json,creator,updater)"
                    + " VALUES (?, 'BUSINESS_FORM','ft07-test','{}','ft07-test','{}',?,?)",
                id,
                Long.toString(actor),
                Long.toString(actor));
        jdbc.update(
                "INSERT INTO public.nocode_work_submission"
                    + " (id,draft_id,idempotency_key,request_digest,material_json,creator,updater)"
                    + " VALUES (?,?,?, ?,jsonb_build_object('taskId',CAST(? AS text)),?,?)",
                id,
                id,
                id,
                "ft07-test",
                taskId,
                Long.toString(actor),
                Long.toString(actor));
    }

    private void assertRolledBack(Task task, String id) {
        assertThat(current(task)).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_work_submission WHERE id=?",
                                Integer.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_work_draft WHERE id=?",
                                Integer.class,
                                id))
                .isZero();
    }

    static class TestGuard implements BpmTaskCompletionGuard {
        private final JdbcTemplate jdbc;
        Runnable nestedCompletion;

        TestGuard(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        public String handler() {
            return "test_material";
        }

        public void validate(
                BpmBusinessTaskDTO task, BpmBusinessTaskCompleteReqDTO command, long actor) {
            if (!Objects.equals(
                    jdbc.queryForObject(
                            "SELECT count(*) FROM public.nocode_work_submission WHERE id=? AND"
                                    + " creator=? AND material_json->>'taskId'=?",
                            Integer.class,
                            command.submissionId(),
                            Long.toString(actor),
                            task.taskId()),
                    1)) throw new IllegalStateException("材料与当前任务不匹配");
            if (nestedCompletion != null) nestedCompletion.run();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({
        com.richuang.os.module.bpm.service.task.BpmMaterialContextServiceImpl.class,
        BpmBusinessTaskServiceImpl.class,
        BpmTaskCompletionGuardListener.class,
        BpmProcessTaskApiImpl.class,
        BpmTaskServiceImpl.class
    })
    static class Fixture {
        @Bean(destroyMethod = "close")
        ProcessEngine processEngine(
                DataSource ds,
                PlatformTransactionManager manager,
                BpmTaskCompletionGuardListener listener,
                org.springframework.context.ApplicationContext applicationContext) {
            var config = new SpringProcessEngineConfiguration();
            config.setApplicationContext(applicationContext);
            config.setDataSource(ds);
            config.setTransactionManager(manager);
            config.setDatabaseSchemaUpdate("false");
            config.setAsyncExecutorActivate(false);
            config.setDisableIdmEngine(true);
            config.setDisableEventRegistry(true);
            config.setEnableConfiguratorServiceLoader(false);
            config.setIdGenerator(new StrongUuidGenerator());
            config.setEventListeners(List.of(listener));
            return config.buildProcessEngine();
        }

        @Bean
        TaskService taskService(ProcessEngine engine) {
            return engine.getTaskService();
        }

        @Bean
        RepositoryService repositoryService(ProcessEngine engine) {
            return engine.getRepositoryService();
        }

        @Bean
        RuntimeService runtimeService(ProcessEngine engine) {
            return engine.getRuntimeService();
        }

        @Bean
        HistoryService historyService(ProcessEngine engine) {
            return engine.getHistoryService();
        }

        @Bean
        ManagementService managementService(ProcessEngine engine) {
            return engine.getManagementService();
        }

        @Bean
        TestGuard testGuard(JdbcTemplate jdbc) {
            return new TestGuard(jdbc);
        }

        @Bean
        BpmModelService modelService() {
            return mock(BpmModelService.class);
        }

        @Bean
        BpmProcessInstanceService processInstanceService() {
            return mock(BpmProcessInstanceService.class);
        }

        @Bean
        BpmProcessDefinitionService bpmProcessDefinitionService() {
            return mock(BpmProcessDefinitionService.class);
        }

        @Bean
        BpmProcessInstanceCopyService processInstanceCopyService() {
            return mock(BpmProcessInstanceCopyService.class);
        }

        @Bean
        BpmMessageService messageService() {
            return mock(BpmMessageService.class);
        }

        @Bean
        BpmFormService formService() {
            return mock(BpmFormService.class);
        }

        @Bean
        AdminUserApi adminUserApi() {
            return mock(AdminUserApi.class);
        }

        @Bean
        DeptApi deptApi() {
            return mock(DeptApi.class);
        }
    }
}
