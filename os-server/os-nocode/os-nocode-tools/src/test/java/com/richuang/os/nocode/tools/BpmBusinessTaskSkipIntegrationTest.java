package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.module.bpm.framework.flowable.config.BpmFlowableConfiguration;
import com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnVariableConstants;
import com.richuang.os.module.bpm.service.definition.BpmBusinessModelValidationServiceImpl;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.delegate.event.AbstractFlowableEngineEventListener;
import org.flowable.engine.interceptor.*;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 复用当前开发库独立引擎，覆盖 skip 不发送任务完成事件的真实执行路径。 */
class BpmBusinessTaskSkipIntegrationTest {
    static ProcessEngine engine;
    BpmTaskCompletionIntegrationTest fixture = new BpmTaskCompletionIntegrationTest();
    int beforeCalls;
    int afterCalls;
    int skippedNodeCompletions;
    boolean injectSkip;
    AbstractFlowableEngineEventListener listener;

    @BeforeAll
    static void open() {
        BpmTaskCompletionIntegrationTest.open();
        engine = BpmTaskCompletionIntegrationTest.engine;
    }

    @AfterAll
    static void close() {
        BpmTaskCompletionIntegrationTest.close();
    }

    @BeforeEach
    void installProductionConfigurer() {
        var configuration =
                (SpringProcessEngineConfiguration) engine.getProcessEngineConfiguration();
        configuration.setEnableProcessDefinitionInfoCache(true);
        configuration.setCreateUserTaskInterceptor(
                new CreateUserTaskInterceptor() {
                    @Override
                    public void beforeCreateUserTask(CreateUserTaskBeforeContext context) {
                        beforeCalls++;
                        if (injectSkip && "work".equals(context.getUserTask().getId()))
                            context.setSkipExpression("${true}");
                    }

                    @Override
                    public void afterCreateUserTask(CreateUserTaskAfterContext context) {
                        afterCalls++;
                    }
                });
        // 与正式启动使用同一装配函数，确保不只是测试里手动替换业务行为。
        new BpmFlowableConfiguration().bpmBusinessTaskCreateConfigurer().configure(configuration);
        listener =
                new AbstractFlowableEngineEventListener(
                        Set.of(FlowableEngineEventType.TASK_COMPLETED)) {
                    @Override
                    protected void taskCompleted(FlowableEngineEntityEvent event) {
                        if ("work".equals(((Task) event.getEntity()).getTaskDefinitionKey()))
                            skippedNodeCompletions++;
                    }
                };
        engine.getRuntimeService().addEventListener(listener);
    }

    @AfterEach
    void clean() {
        engine.getRuntimeService().removeEventListener(listener);
        ((SpringProcessEngineConfiguration) engine.getProcessEngineConfiguration())
                .setCreateUserTaskInterceptor(null);
        fixture.clean();
    }

    @ParameterizedTest
    @ValueSource(strings = {"${true}", "${false}", " "})
    void publicationRejectsEveryNonEmptyBusinessSkipExpression(String skip) {
        byte[] xml = xml("skip_publish", true, skip).getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(
                        () -> new BpmBusinessModelValidationServiceImpl().validate(xml, 0, 10001L))
                .hasMessageContaining("不允许配置跳过表达式");
    }

    @Test
    void ordinarySkipIsStillPublishable() {
        byte[] xml = xml("skip_normal_publish", false, "${true}").getBytes(StandardCharsets.UTF_8);
        assertThatCode(() -> new BpmBusinessModelValidationServiceImpl().validate(xml, 0, 10001L))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"${true}", "${false}", " "})
    void alreadyDeployedBusinessSkipCannotCompletePredecessorOrAdvance(String skip) {
        Task gate = start(true, skip);
        assertThatThrownBy(() -> engine.getTaskService().complete(gate.getId()))
                .hasMessageContaining("不允许配置跳过表达式");
        assertPredecessorRolledBack(gate);
        assertThat(skippedNodeCompletions).isZero();
    }

    @Test
    void ordinarySkipRetainsItsNoCompletionEventBehavior() {
        Task gate = start(false, "${true}");
        engine.getTaskService().complete(gate.getId());
        assertThat(current(gate).getTaskDefinitionKey()).isEqualTo("after");
        assertThat(skippedNodeCompletions).isZero();
        // 保留此前钩子：三个节点均经过 before，真正创建的 gate/after 经过 after。
        assertThat(beforeCalls).isEqualTo(3);
        assertThat(afterCalls).isEqualTo(2);
    }

    @Test
    void businessWithoutSkipStillCreatesAndRequiresCompletionMaterial() {
        Task gate = start(true, null);
        engine.getTaskService().complete(gate.getId());
        Task work = current(gate);
        assertThat(work.getTaskDefinitionKey()).isEqualTo("work");
        assertThat(beforeCalls).isEqualTo(2);
        assertThat(afterCalls).isEqualTo(2);
        assertThatThrownBy(() -> engine.getTaskService().complete(work.getId()))
                .hasMessageContaining("业务办理入口");
        assertThat(current(gate).getId()).isEqualTo(work.getId());
    }

    @Test
    void dynamicBpmnSkipOverrideCannotBypassDeployedBinding() {
        Task gate = start(true, null);
        var properties = engine.getDynamicBpmnService().changeSkipExpression("work", "${true}");
        engine.getDynamicBpmnService()
                .saveProcessDefinitionInfo(gate.getProcessDefinitionId(), properties);
        assertThatThrownBy(() -> engine.getTaskService().complete(gate.getId()))
                .hasMessageContaining("不允许配置跳过表达式");
        assertPredecessorRolledBack(gate);
    }

    @Test
    void existingCreateInterceptorCannotIntroduceBusinessSkip() {
        Task gate = start(true, null);
        injectSkip = true;
        assertThatThrownBy(() -> engine.getTaskService().complete(gate.getId()))
                .hasMessageContaining("不允许配置跳过表达式");
        assertPredecessorRolledBack(gate);
    }

    @Test
    void runtimeVariablesCannotTurnOffBusinessBindingProtection() {
        Task gate = start(true, "${true}");
        engine.getRuntimeService()
                .setVariables(
                        gate.getProcessInstanceId(),
                        Map.of(
                                BpmnVariableConstants.PROCESS_INSTANCE_SKIP_EXPRESSION_ENABLED,
                                false,
                                "handler",
                                "",
                                "business",
                                false));
        assertThatThrownBy(() -> engine.getTaskService().complete(gate.getId()))
                .hasMessageContaining("不允许配置跳过表达式");
        assertPredecessorRolledBack(gate);
    }

    private void assertPredecessorRolledBack(Task gate) {
        assertThat(current(gate).getId()).isEqualTo(gate.getId());
        assertThat(
                        engine.getHistoryService()
                                .createHistoricTaskInstanceQuery()
                                .taskId(gate.getId())
                                .singleResult()
                                .getEndTime())
                .isNull();
        assertThat(
                        engine.getHistoryService()
                                .createHistoricTaskInstanceQuery()
                                .processInstanceId(gate.getProcessInstanceId())
                                .taskDefinitionKey("after")
                                .count())
                .isZero();
    }

    private Task current(Task gate) {
        return engine.getTaskService()
                .createTaskQuery()
                .processInstanceId(gate.getProcessInstanceId())
                .singleResult();
    }

    private Task start(boolean business, String skip) {
        String key = "skip_guard_" + UUID.randomUUID().toString().replace("-", "");
        // 直接向引擎部署，模拟应用新保护上线之前已经发布的模型。
        var deployment =
                engine.getRepositoryService()
                        .createDeployment()
                        .addString(key + ".bpmn20.xml", xml(key, business, skip))
                        .deploy();
        fixture.deployments.add(deployment.getId());
        var process =
                engine.getRuntimeService()
                        .startProcessInstanceByKey(
                                key,
                                Map.of(
                                        BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_STATUS,
                                        1,
                                        BpmnVariableConstants
                                                .PROCESS_INSTANCE_SKIP_EXPRESSION_ENABLED,
                                        true));
        return engine.getTaskService()
                .createTaskQuery()
                .processInstanceId(process.getId())
                .singleResult();
    }

    private String xml(String key, boolean business, String skip) {
        String binding =
                business ? " business:handler=\"test_material\" business:configuration=\"{}\"" : "";
        String expression = skip == null ? "" : " flowable:skipExpression=\"" + skip + "\"";
        return """
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:flowable="http://flowable.org/bpmn" xmlns:business="https://richuang.com/schema/bpmn/business-task" targetNamespace="skip_guard">
  <process id="%s" isExecutable="true">
    <startEvent id="start"/><sequenceFlow id="a" sourceRef="start" targetRef="gate"/>
    <userTask id="gate" flowable:assignee="10001"/>
    <sequenceFlow id="b" sourceRef="gate" targetRef="work"/>
    <userTask id="work" name="填写业务材料" flowable:assignee="10001"%s%s/>
    <sequenceFlow id="c" sourceRef="work" targetRef="after"/>
    <userTask id="after" flowable:assignee="10001"/>
    <sequenceFlow id="d" sourceRef="after" targetRef="end"/><endEvent id="end"/>
  </process>
</definitions>
"""
                .formatted(key, binding, expression);
    }
}
