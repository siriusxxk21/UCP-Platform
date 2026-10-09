package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.module.bpm.controller.admin.definition.vo.model.BpmModelMetaInfoVO;
import com.lingan.ucp.module.bpm.controller.admin.task.vo.instance.BpmApprovalDetailRespVO;
import com.lingan.ucp.module.bpm.controller.admin.task.vo.instance.BpmProcessInstanceCreateReqVO;
import com.lingan.ucp.module.bpm.controller.admin.task.vo.task.BpmTaskApproveReqVO;
import com.lingan.ucp.module.bpm.dal.dataobject.definition.BpmFormDO;
import com.lingan.ucp.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import com.lingan.ucp.module.bpm.enums.definition.BpmModelFormTypeEnum;
import com.lingan.ucp.module.bpm.framework.flowable.core.enums.BpmnVariableConstants;
import com.lingan.ucp.module.bpm.service.definition.BpmFormService;
import com.lingan.ucp.module.bpm.service.definition.BpmNodeFormServiceImpl;
import com.lingan.ucp.module.bpm.service.definition.BpmProcessDefinitionService;
import com.lingan.ucp.module.bpm.service.task.BpmProcessInstanceService;
import com.lingan.ucp.module.bpm.service.task.BpmProcessInstanceServiceImpl;
import com.lingan.ucp.module.bpm.service.task.BpmTaskService;

import org.flowable.engine.ProcessEngine;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 无发起表单的公开 Service 发起入口回归：复用当前开发库与真实 Flowable 引擎。
 *
 * <p>定义元信息、发起权限与审批预览使用测试替身；实例创建、节点发布解析、表单校验及任务完成均执行真实代码。 不覆盖 HTTP 身份认证、真实角色权限和设计器保存；UUID 部署仅清理本次夹具。
 */
class BpmNoStartFormIntegrationTest {
    static ProcessEngine engine;
    static BpmTaskService tasks;
    final BpmTaskCompletionIntegrationTest fixture = new BpmTaskCompletionIntegrationTest();
    BpmProcessDefinitionService definitions;
    BpmProcessInstanceService instances;

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
        definitions =
                BpmTaskCompletionIntegrationTest.context.getBean(BpmProcessDefinitionService.class);
        var info = new BpmProcessDefinitionInfoDO();
        info.setFormType(BpmModelFormTypeEnum.NONE.getType());
        when(definitions.getProcessDefinitionInfo(anyString())).thenReturn(info);
        when(definitions.canUserStartProcessDefinition(any(), eq(10001L))).thenReturn(true);
        doAnswer(
                        invocation ->
                                engine.getRepositoryService()
                                        .createProcessDefinitionQuery()
                                        .processDefinitionId(invocation.getArgument(0))
                                        .singleResult())
                .when(definitions)
                .getProcessDefinition(anyString());
        doAnswer(
                        invocation ->
                                engine.getRepositoryService()
                                        .getBpmnModel(invocation.getArgument(0)))
                .when(definitions)
                .getProcessDefinitionBpmnModel(anyString());

        var service = spy(new BpmProcessInstanceServiceImpl());
        ReflectionTestUtils.setField(service, "runtimeService", engine.getRuntimeService());
        ReflectionTestUtils.setField(service, "processDefinitionService", definitions);
        var preview = new BpmApprovalDetailRespVO();
        preview.setActivityNodes(new ArrayList<>());
        doReturn(preview).when(service).getApprovalDetail(eq(10001L), any());
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(
                new TransactionInterceptor(
                        BpmTaskCompletionIntegrationTest.tool.getBean(
                                PlatformTransactionManager.class),
                        new AnnotationTransactionAttributeSource()));
        instances = (BpmProcessInstanceService) proxy.getProxy();
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void noStartFormCreatesThroughPublicEntryAndCompletesFormlessTask() {
        String definitionId = deploy(false);
        String instanceId =
                instances.createProcessInstance(10001L, request(definitionId, Map.of()));
        assertThat(
                        engine.getRuntimeService()
                                .getVariable(
                                        instanceId,
                                        BpmnVariableConstants
                                                .PROCESS_INSTANCE_VARIABLE_START_USER_ID))
                .isEqualTo(10001L);
        Task task = current(instanceId);
        assertThat(task).isNotNull();
        var todo = tasks.getTodoTask(10001L, task.getId(), instanceId);
        assertThat(todo.getFormFields()).isNullOrEmpty();
        tasks.approveTask(
                10001L,
                new BpmTaskApproveReqVO()
                        .setId(task.getId())
                        .setReason("无发起表单审批")
                        .setVariables(Map.of()));
        assertThat(current(instanceId)).isNull();
        assertThat(
                        engine.getRuntimeService()
                                .createProcessInstanceQuery()
                                .processInstanceId(instanceId)
                                .count())
                .isZero();
        assertThat(
                        engine.getHistoryService()
                                .createHistoricProcessInstanceQuery()
                                .processInstanceId(instanceId)
                                .finished()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void noStartFormRejectsInjectedFieldsBeforeCreatingAnyInstance() {
        String definitionId = deploy(false);
        assertThatThrownBy(
                        () ->
                                instances.createProcessInstance(
                                        10001L,
                                        request(
                                                definitionId,
                                                Map.of("forgedBusinessField", "不能注入"))))
                .hasMessageContaining("不能提交额外业务字段");
        assertThat(
                        engine.getRuntimeService()
                                .createProcessInstanceQuery()
                                .processDefinitionId(definitionId)
                                .count())
                .isZero();
        assertThat(
                        engine.getHistoryService()
                                .createHistoricProcessInstanceQuery()
                                .processDefinitionId(definitionId)
                                .count())
                .isZero();
        assertThat(
                        engine.getTaskService()
                                .createTaskQuery()
                                .processDefinitionId(definitionId)
                                .count())
                .isZero();
    }

    @Test
    void independentFlowFormStillRequiresItsOwnPublishedFieldsWhenStartHasNone() {
        String definitionId = deploy(true);
        String instanceId =
                instances.createProcessInstance(10001L, request(definitionId, Map.of()));
        Task task = current(instanceId);
        var todo = tasks.getTodoTask(10001L, task.getId(), instanceId);
        assertThat(todo.getFormName()).isEqualTo("独立节点登记表");
        assertThat(todo.getFormFields()).hasSize(1);
        assertThat(todo.getFormBinding().path("mode").asText()).isEqualTo("OVERRIDE");

        var approval =
                new BpmTaskApproveReqVO()
                        .setId(task.getId())
                        .setReason("独立表单校验")
                        .setVariables(Map.of());
        assertThatThrownBy(() -> tasks.approveTask(10001L, approval)).hasMessageContaining("必填");
        assertThat(current(instanceId)).isNotNull();
        assertThat(engine.getTaskService().getTaskComments(task.getId())).isEmpty();
        approval.setVariables(Map.of("answer", "已填写", "extra", "未配置"));
        assertThatThrownBy(() -> tasks.approveTask(10001L, approval)).hasMessageContaining("不可写");
        assertThat(current(instanceId)).isNotNull();
        approval.setVariables(Map.of("answer", "独立节点材料"));
        tasks.approveTask(10001L, approval);
        assertThat(current(instanceId)).isNull();
        assertThat(
                        com.lingan.ucp.module.bpm.framework.flowable.core.util.FlowableUtils
                                .getTaskFormVariable(tasks.getHistoricTask(task.getId())))
                .containsEntry("answer", "独立节点材料");
    }

    private BpmProcessInstanceCreateReqVO request(
            String definitionId, Map<String, Object> variables) {
        var request = new BpmProcessInstanceCreateReqVO();
        request.setProcessDefinitionId(definitionId);
        request.setVariables(variables);
        return request;
    }

    private Task current(String instanceId) {
        return engine.getTaskService()
                .createTaskQuery()
                .processInstanceId(instanceId)
                .singleResult();
    }

    private String deploy(boolean independentForm) {
        var resolver = new BpmNodeFormServiceImpl();
        var formService = mock(BpmFormService.class);
        ReflectionTestUtils.setField(resolver, "formService", formService);
        var form = new BpmFormDO();
        form.setId(99997L);
        form.setName("独立节点登记表");
        form.setConf("{}");
        form.setFields(
                List.of(
                        "{\"type\":\"input\",\"field\":\"answer\",\"validate\":[{\"required\":true}]}"));
        when(formService.getForm(99997L)).thenReturn(form);
        var metadata = new BpmModelMetaInfoVO();
        metadata.setFormType(BpmModelFormTypeEnum.NONE.getType());
        String key = "no_start_form_" + UUID.randomUUID().toString().replace("-", "");
        String binding =
                independentForm
                        ? " node:configuration=\"{&quot;mode&quot;:&quot;OVERRIDE&quot;,&quot;source&quot;:{&quot;kind&quot;:&quot;FLOW_FORM&quot;,&quot;formId&quot;:&quot;99997&quot;}}\""
                        : "";
        String xml =
                """
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn"
 xmlns:node="urn:ucp-platform:bpmn:node-form" targetNamespace="no-start-form-test">
 <process id="%s" name="无需发起表单回归" isExecutable="true">
  <startEvent id="start"/><sequenceFlow id="a" sourceRef="start" targetRef="work"/>
  <userTask id="work" name="独立人工节点" flowable:assignee="10001"%s/>
  <sequenceFlow id="b" sourceRef="work" targetRef="end"/><endEvent id="end"/>
 </process>
</definitions>
"""
                        .formatted(key, binding);
        byte[] resolved =
                resolver.resolveForDeployment(xml.getBytes(StandardCharsets.UTF_8), metadata);
        var deployed =
                engine.getRepositoryService()
                        .createDeployment()
                        .addBytes(key + ".bpmn20.xml", resolved)
                        .deploy();
        fixture.deployments.add(deployed.getId());
        return engine.getRepositoryService()
                .createProcessDefinitionQuery()
                .deploymentId(deployed.getId())
                .singleResult()
                .getId();
    }
}
