package com.richuang.os.module.bpm.service.definition;

import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.richuang.os.module.bpm.enums.ErrorCodeConstants.*;
import static com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnModelConstants.*;
import static com.richuang.os.module.bpm.framework.flowable.core.util.BpmnModelUtils.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.richuang.os.framework.common.util.json.JsonUtils;
import com.richuang.os.module.bpm.api.task.BpmTaskCenterNodeHandler;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmFormDO;
import com.richuang.os.module.bpm.enums.definition.BpmSimpleModelNodeTypeEnum;
import com.richuang.os.module.bpm.framework.flowable.core.listener.BpmTaskCenterNodeListener;
import com.richuang.os.module.bpm.framework.flowable.core.util.BpmNodeFormUtils;

import jakarta.annotation.Resource;

import org.flowable.bpmn.model.*;
import org.flowable.engine.delegate.ExecutionListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 发布时解析任务配置，不修改草稿或运行中的旧版本，不创建真实任务实例。 */
@Service
public class BpmTaskCenterNodeModelServiceImpl implements BpmTaskCenterNodeModelService {
    @Resource private ObjectProvider<BpmTaskCenterNodeHandler> handlers;

    @Override
    public Deployment prepare(byte[] bpmn, String simpleJson, BpmFormDO flowForm, long actor) {
        BpmnModel model = getBpmnModel(bpmn);
        Map<String, JsonNode> snapshots = new LinkedHashMap<>();
        List<FlowNode> nodes =
                model.getProcesses().stream()
                        .flatMap(
                                process ->
                                        process
                                                .findFlowElementsOfType(FlowNode.class, true)
                                                .stream())
                        .toList();
        Map<String, JsonNode> fields =
                flowForm == null ? Map.of() : BpmNodeFormUtils.fields(flowForm);
        for (FlowNode node : nodes) {
            String configuration = parseExtensionElement(node, TASK_CENTER_CONFIG);
            if (!Objects.equals(
                            parseNodeType(node),
                            BpmSimpleModelNodeTypeEnum.TASK_CENTER_NODE.getType())
                    && configuration == null) continue;
            if (!(node instanceof ReceiveTask task)
                    || task.getLoopCharacteristics() != null
                    || task.getSkipExpression() != null && !task.getSkipExpression().isBlank()
                    || nodes.stream()
                            .anyMatch(
                                    element ->
                                            element instanceof BoundaryEvent event
                                                    && node.getId()
                                                            .equals(event.getAttachedToRefId()))
                    || reachesAgain(node, node.getId(), new HashSet<>())) {
                throw exception(
                        TASK_CENTER_NODE_INVALID,
                        node.getName(),
                        "任务节点须为独立等待节点，不能自动跳过、超时推进、循环返回或配置会签");
            }
            if (configuration == null
                    || configuration.isBlank()
                    || node.getName() == null
                    || node.getName().isBlank()) {
                throw exception(TASK_CENTER_NODE_INVALID, node.getName(), "请填写节点名称并配置任务");
            }
            List<BpmTaskCenterNodeHandler> available = handlers.stream().toList();
            if (available.size() != 1) throw exception(TASK_CENTER_NODE_HANDLER_UNAVAILABLE);
            BpmTaskCenterNodeHandler handler = available.getFirst();
            String frozen = handler.prepare(node.getId(), configuration, actor);
            for (String field : handler.neededFields(frozen)) {
                JsonNode definition = fields.get(field);
                if (definition == null) {
                    throw exception(
                            TASK_CENTER_NODE_INVALID, node.getName(), "人员来源引用的流程表单字段不存在：" + field);
                }
            }
            node.getExtensionElements().remove(TASK_CENTER_CONFIG);
            node.getExtensionElements().remove(NODE_TYPE);
            addExtensionElement(node, TASK_CENTER_CONFIG, frozen);
            addExtensionElement(
                    node, NODE_TYPE, BpmSimpleModelNodeTypeEnum.TASK_CENTER_NODE.getType());
            FlowableListener listener = new FlowableListener();
            listener.setEvent(ExecutionListener.EVENTNAME_START);
            listener.setImplementationType(
                    ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
            listener.setImplementation("${" + BpmTaskCenterNodeListener.BEAN_NAME + "}");
            node.setExecutionListeners(List.of(listener));
            snapshots.put(node.getId(), JsonUtils.parseObject(frozen, JsonNode.class));
        }
        if (snapshots.isEmpty()) return new Deployment(bpmn, simpleJson);
        String resolvedSimple = simpleJson;
        if (simpleJson != null && !simpleJson.isBlank()) {
            JsonNode simple = JsonUtils.parseObject(simpleJson, JsonNode.class);
            freezeSimple(simple, snapshots);
            resolvedSimple = JsonUtils.toJsonString(simple);
        }
        return new Deployment(getBpmnXml(model).getBytes(StandardCharsets.UTF_8), resolvedSimple);
    }

    private void freezeSimple(JsonNode node, Map<String, JsonNode> snapshots) {
        if (!(node instanceof ObjectNode object)) return;
        JsonNode snapshot = snapshots.get(node.path("id").asText());
        if (snapshot != null) object.set("taskCenterSetting", snapshot);
        freezeSimple(node.get("childNode"), snapshots);
        for (JsonNode branch : node.path("conditionNodes")) freezeSimple(branch, snapshots);
    }

    /** 当前任务节点只处理一次到达；禁止从路由分支绕回产生不明确的任务回合。 */
    private boolean reachesAgain(FlowNode node, String origin, Set<String> visited) {
        if (!visited.add(node.getId())) return false;
        for (SequenceFlow sequence : node.getOutgoingFlows()) {
            if (origin.equals(sequence.getTargetRef())) return true;
            if (sequence.getTargetFlowElement() instanceof FlowNode next
                    && reachesAgain(next, origin, visited)) return true;
        }
        return false;
    }
}
