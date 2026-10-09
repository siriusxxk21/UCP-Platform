package com.richuang.os.module.bpm.service.definition;

import static com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnModelConstants.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.richuang.os.framework.common.util.json.JsonUtils;
import com.richuang.os.module.bpm.api.task.BpmTaskCenterNodeHandler;
import com.richuang.os.module.bpm.controller.admin.definition.vo.model.simple.BpmSimpleModelNodeVO;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmFormDO;
import com.richuang.os.module.bpm.framework.flowable.core.util.BpmnModelUtils;
import com.richuang.os.module.bpm.framework.flowable.core.util.SimpleModelUtils;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.ReceiveTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** 仅覆盖任务中心节点编译与发布冻结，不启动数据库、不回归其他节点。 */
class BpmTaskCenterNodeModelTest {
    private BpmTaskCenterNodeModelServiceImpl service;
    private BpmTaskCenterNodeHandler handler;

    @BeforeEach
    void setup() {
        service = new BpmTaskCenterNodeModelServiceImpl();
        handler = mock(BpmTaskCenterNodeHandler.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BpmTaskCenterNodeHandler> provider = mock(ObjectProvider.class);
        when(provider.stream()).thenAnswer(ignored -> Stream.of(handler));
        ReflectionTestUtils.setField(service, "handlers", provider);
        when(handler.neededFields(anyString())).thenReturn(Set.of());
        when(handler.prepare(anyString(), anyString(), anyLong()))
                .thenReturn("{\"version\":1,\"publisherId\":7,\"templateVersion\":2}");
    }

    @Test
    void compilesWaitingNodeWithoutApprovalAndFreezesDeploymentOnly() {
        BpmSimpleModelNodeVO node = node();
        BpmnModel model = SimpleModelUtils.buildBpmnModel("TaskFlow", "任务流程", node);
        assertEquals(
                0,
                BpmnModelUtils.getBpmnModelElements(model, org.flowable.bpmn.model.UserTask.class)
                        .size());
        ReceiveTask receive = (ReceiveTask) model.getMainProcess().getFlowElement("taskNode");
        assertEquals(1, receive.getExecutionListeners().size());
        assertEquals(
                "${bpmTaskCenterNodeListener}",
                receive.getExecutionListeners().getFirst().getImplementation());
        byte[] draft = BpmnModelUtils.getBpmnXml(model).getBytes(StandardCharsets.UTF_8);
        BpmTaskCenterNodeModelService.Deployment deployed =
                service.prepare(draft, JsonUtils.toJsonString(node), null, 7);
        ReceiveTask published =
                (ReceiveTask)
                        BpmnModelUtils.getBpmnModel(deployed.bpmn())
                                .getMainProcess()
                                .getFlowElement("taskNode");
        assertEquals(
                2,
                JsonUtils.parseObject(
                                BpmnModelUtils.parseExtensionElement(published, TASK_CENTER_CONFIG),
                                JsonNode.class)
                        .path("templateVersion")
                        .asInt());
        assertEquals(
                2,
                JsonUtils.parseObject(deployed.simpleJson(), JsonNode.class)
                        .path("taskCenterSetting")
                        .path("templateVersion")
                        .asInt());
        assertFalse(new String(draft, StandardCharsets.UTF_8).contains("publisherId"));
        assertFalse(node.getTaskCenterSetting().has("publisherId"));
        verify(handler, times(1)).prepare(eq("taskNode"), anyString(), eq(7L));
    }

    @Test
    void rejectsMissingConfigAndMissingDynamicPersonField() {
        BpmSimpleModelNodeVO node = node();
        node.setTaskCenterSetting(null);
        assertThrows(RuntimeException.class, () -> service.prepare(xml(node), null, null, 7));
        when(handler.neededFields(anyString())).thenReturn(Set.of("worker"));
        assertThrows(RuntimeException.class, () -> service.prepare(xml(node()), null, null, 7));
        BpmFormDO form =
                new BpmFormDO().setFields(List.of("{\"field\":\"worker\",\"type\":\"input\"}"));
        assertDoesNotThrow(() -> service.prepare(xml(node()), null, form, 7));
    }

    @Test
    void rejectsMultiInstanceAndSkipBypass() {
        BpmnModel model = SimpleModelUtils.buildBpmnModel("TaskFlow", "任务流程", node());
        ReceiveTask receive = (ReceiveTask) model.getMainProcess().getFlowElement("taskNode");
        receive.setSkipExpression("${true}");
        assertThrows(
                RuntimeException.class,
                () ->
                        service.prepare(
                                BpmnModelUtils.getBpmnXml(model).getBytes(StandardCharsets.UTF_8),
                                null,
                                null,
                                7));
        receive.setSkipExpression(null);
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setLoopCardinality("2");
        receive.setLoopCharacteristics(loop);
        byte[] multiInstanceXml = BpmnModelUtils.getBpmnXml(model).getBytes(StandardCharsets.UTF_8);
        assertNotNull(
                ((ReceiveTask)
                                BpmnModelUtils.getBpmnModel(multiInstanceXml)
                                        .getMainProcess()
                                        .getFlowElement("taskNode"))
                        .getLoopCharacteristics(),
                "应使用实际写入 XML 的会签配置验证发布拒绝，空配置会被转换器省略");
        assertThrows(
                RuntimeException.class, () -> service.prepare(multiInstanceXml, null, null, 7));
    }

    private byte[] xml(BpmSimpleModelNodeVO node) {
        return BpmnModelUtils.getBpmnXml(SimpleModelUtils.buildBpmnModel("TaskFlow", "任务流程", node))
                .getBytes(StandardCharsets.UTF_8);
    }

    private BpmSimpleModelNodeVO node() {
        return new BpmSimpleModelNodeVO()
                .setId("taskNode")
                .setName("任务中心")
                .setType(16)
                .setTaskCenterSetting(
                        JsonUtils.parseObject(
                                "{\"version\":1,\"source\":\"TEMPLATE\"}", JsonNode.class))
                .setChildNode(new BpmSimpleModelNodeVO().setId("end").setName("结束").setType(1));
    }
}
