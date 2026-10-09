package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.runtime.service.rules.ScriptedFieldRuleEvaluator.*;
import static com.lingan.ucp.nocode.tools.FieldRuleEnforcementFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.lingan.ucp.module.bpm.api.task.BpmBusinessTaskBinding;
import com.lingan.ucp.module.bpm.service.definition.BpmModelService;
import com.lingan.ucp.module.bpm.service.task.BpmProcessInstanceService;
import com.lingan.ucp.nocode.api.workflow.FlowTasks;
import com.lingan.ucp.nocode.work.dal.mapper.event.WorkEventMapper;
import com.lingan.ucp.nocode.workflow.dal.mapper.task.FlowTaskBindingMapper;
import com.lingan.ucp.nocode.workflow.service.task.FlowTaskService;

import org.flowable.engine.ProcessEngine;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.*;

/** 流程任务办理提交走公共保存流水线：只读联动在真实 Flowable 任务提交时同样被服务端重算强制。 */
class FieldRuleFlowTaskIntegrationTest {
    static AnnotationConfigApplicationContext flowContext;
    static ProcessEngine engine;
    static FlowTaskService flows;
    private FieldRuleEnforcementFixture fx;
    private final List<String> deployments = new ArrayList<>();
    private final List<String> taskIds = new ArrayList<>();

    @BeforeAll
    static void open() throws Exception {
        connect();
        session.getConfiguration().addMapper(FlowTaskBindingMapper.class);
        session.getConfiguration().addMapper(WorkEventMapper.class);
        flowContext = new AnnotationConfigApplicationContext();
        flowContext.setParent(servicesContext);
        flowContext.register(FlowTaskIntegrationTest.Fixture.class);
        flowContext.addBeanFactoryPostProcessor(
                factory ->
                        factory.getBeanDefinition(
                                        com.lingan.ucp.module.bpm.api.task.BpmProcessTaskApiImpl
                                                .class
                                                .getName())
                                .setPrimary(true));
        flowContext.refresh();
        engine = flowContext.getBean(ProcessEngine.class);
        flows = flowContext.getBean(FlowTaskService.class);
        when(flowContext.getBean(BpmModelService.class).getBpmnModelByDefinitionId(anyString()))
                .thenAnswer(i -> engine.getRepositoryService().getBpmnModel(i.getArgument(0)));
        when(flowContext.getBean(BpmProcessInstanceService.class).getProcessInstance(anyString()))
                .thenAnswer(
                        i ->
                                engine.getRuntimeService()
                                        .createProcessInstanceQuery()
                                        .processInstanceId(i.getArgument(0))
                                        .includeProcessVariables()
                                        .singleResult());
    }

    @AfterAll
    static void shutdown() {
        if (flowContext != null) flowContext.close();
        close();
    }

    @BeforeEach
    void setup() {
        fx = new FieldRuleEnforcementFixture().objects();
        fx.rule(fx.bank, fx.linkage(true, fx.company)).app();
        fx.evaluator.linkage(fx.bank, v -> applied("银行-" + v.get(fx.company)));
    }

    @AfterEach
    void cleanup() {
        for (var id : deployments) engine.getRepositoryService().deleteDeployment(id, true);
        for (var id : taskIds) {
            jdbc.update(
                    "DELETE FROM public.nocode_work_event WHERE source_type='FLOW_TASK' AND"
                            + " source_id=?",
                    id);
            jdbc.update("DELETE FROM public.nocode_flow_task_binding WHERE task_id=?", id);
        }
        if (fx != null) fx.close();
    }

    @Test
    void flowTaskSubmitForcesReadonlyLinkage() throws Exception {
        var task = start();
        var draft =
                flows.saveDraft(
                        new FlowTasks.Save(
                                task.getId(),
                                null,
                                null,
                                values(fx.name, "流程", fx.company, "甲", fx.bank, "伪造")),
                        10001);
        var material =
                flows.submit(
                        new FlowTasks.Submit(
                                task.getId(),
                                draft.id(),
                                draft.revision(),
                                UUID.randomUUID().toString(),
                                "流程材料提交"),
                        10001);
        assertThat(fx.get(material.recordId()).record().values()).containsEntry(fx.bank, "银行-甲");
    }

    private Task start() throws Exception {
        var config =
                mapper.writeValueAsString(
                        new FlowTasks.Configuration(fx.form, fx.voucher.objectId(), "CREATE"));
        var key = "flow_rule_" + UUID.randomUUID().toString().replace("-", "");
        var escaped =
                config.replace("&", "&amp;")
                        .replace("\"", "&quot;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;");
        var xml =
                """
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:os="%s" targetNamespace="flow_rule">
  <process id="%s" isExecutable="true">
    <startEvent id="start"/><sequenceFlow id="a" sourceRef="start" targetRef="work"/>
    <userTask id="work" name="业务办理" flowable:assignee="10001" os:handler="nocode" os:configuration="%s"/>
    <sequenceFlow id="b" sourceRef="work" targetRef="end"/><endEvent id="end"/>
  </process>
</definitions>
"""
                        .formatted(BpmBusinessTaskBinding.NAMESPACE, key, escaped);
        var deployment =
                engine.getRepositoryService()
                        .createDeployment()
                        .name(key)
                        .addString(key + ".bpmn20.xml", xml)
                        .deploy();
        deployments.add(deployment.getId());
        var instance = engine.getRuntimeService().startProcessInstanceByKey(key);
        var task =
                engine.getTaskService()
                        .createTaskQuery()
                        .processInstanceId(instance.getId())
                        .singleResult();
        taskIds.add(task.getId());
        return task;
    }
}
