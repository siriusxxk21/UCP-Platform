package com.richuang.os.module.bpm.service.definition;

import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.richuang.os.module.bpm.enums.ErrorCodeConstants.MODEL_NODE_FORM_INVALID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.richuang.os.framework.common.util.json.JsonUtils;
import com.richuang.os.module.bpm.api.task.BpmBusinessTaskBinding;
import com.richuang.os.module.bpm.api.task.BpmNodeFormBinding;
import com.richuang.os.module.bpm.controller.admin.definition.vo.model.BpmModelMetaInfoVO;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmFormDO;
import com.richuang.os.module.bpm.enums.definition.BpmModelFormTypeEnum;
import com.richuang.os.module.bpm.framework.flowable.core.util.BpmnModelUtils;

import jakarta.annotation.Resource;

import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/** 只改变本次发布内容，不回写设计草稿或旧部署；业务提交仍交由来源 Guard 校验。 */
@Service
public class BpmNodeFormServiceImpl implements BpmNodeFormService {
    @Resource private BpmFormService formService;

    @Override
    public byte[] resolveForDeployment(byte[] xml, BpmModelMetaInfoVO model) {
        var bpmn = BpmnModelUtils.getBpmnModel(xml);
        if (bpmn == null) throw invalid("流程图", "无法解析流程定义");
        boolean changed = false;
        for (var node : BpmnModelUtils.getBpmnModelElements(bpmn, UserTask.class)) {
            String raw =
                    node.getAttributeValue(
                            BpmNodeFormBinding.NAMESPACE, BpmNodeFormBinding.CONFIGURATION);
            if (raw == null) {
                // 只有新声明“无需发起表单”的部署补齐有效配置；旧显式业务/表单绑定仍保留。
                if (model == null
                        || !BpmModelFormTypeEnum.NONE.getType().equals(model.getFormType())
                        || node.getAttributeValue(
                                        BpmBusinessTaskBinding.NAMESPACE,
                                        BpmBusinessTaskBinding.HANDLER_ATTRIBUTE)
                                != null
                        || node.getFormKey() != null) continue;
                raw = "{\"mode\":\"INHERIT\"}";
            }
            var binding = object(raw, node.getName());
            var review = binding.get("materialReview");
            if (review != null
                    && (!review.isObject()
                            || review.size() != 2
                            || !java.util.Set.of("PREVIOUS", "NONE")
                                    .contains(review.path("scope").asText())
                            || !java.util.Set.of("BUSINESS", "TASK")
                                    .contains(review.path("access").asText()))) {
                throw exception(MODEL_NODE_FORM_INVALID, node.getName(), "审批材料查阅配置无效");
            }
            if (review != null
                    && "TASK".equals(review.path("access").asText())
                    && "PREVIOUS".equals(review.path("scope").asText())
                    && node instanceof org.flowable.bpmn.model.UserTask
                    && com.richuang.os.module.bpm.framework.flowable.core.util.BpmnModelUtils
                                    .parseApproveType(node)
                            != null
                    && !Integer.valueOf(1)
                            .equals(
                                    com.richuang.os.module.bpm.framework.flowable.core.util
                                            .BpmnModelUtils.parseApproveType(node))) {
                throw exception(
                        MODEL_NODE_FORM_INVALID, node.getName(), "任务范围材料查阅仅用于人工办理节点，自动审核节点不能授予此权限");
            }
            removePreviouslyResolvedBusiness(node);
            String mode = binding.path("mode").asText();
            JsonNode source;
            if (BpmNodeFormBinding.Mode.INHERIT.name().equals(mode)) {
                // 显式继承不能同时携带独立来源，避免两个设计器产生不同优先级。
                if (binding.hasNonNull("source"))
                    throw invalid(node.getName(), "沿用发起表单时不能同时配置独立来源");
                source = inherited(model);
            } else if (BpmNodeFormBinding.Mode.OVERRIDE.name().equals(mode)) {
                source = binding.path("source");
            } else throw invalid(node.getName(), "请选择沿用发起表单或独立配置");
            if (!source.isObject()) throw invalid(node.getName(), "缺少表单来源");
            var resolved = binding.deepCopy();
            resolved.set("source", source.deepCopy());
            String kind = source.path("kind").asText();
            String taskMode = binding.path("taskMode").asText("");
            if (!taskMode.isEmpty()
                    && !BpmNodeFormBinding.TaskMode.APPROVAL.name().equals(taskMode)
                    && !BpmNodeFormBinding.TaskMode.BUSINESS.name().equals(taskMode))
                throw invalid(node.getName(), "节点完成方式尚未支持");
            if (BpmNodeFormBinding.TaskMode.BUSINESS.name().equals(taskMode)
                    && !BpmNodeFormBinding.Source.APPLICATION_RESOURCE.name().equals(kind))
                throw invalid(node.getName(), "业务任务必须绑定可提交的应用表单");
            if (BpmNodeFormBinding.TaskMode.APPROVAL.name().equals(taskMode)
                    && BpmNodeFormBinding.Source.APPLICATION_RESOURCE.name().equals(kind))
                throw invalid(node.getName(), "业务表单必须通过业务任务提交，不能作为普通审批放行");
            if (BpmNodeFormBinding.Source.NONE.name().equals(kind)) {
                rejectConflictingBusiness(node);
                node.setFormKey(null);
                var permissions = BpmnModelUtils.parseFormFieldsPermission(bpmn, node.getId());
                if (permissions != null && !permissions.isEmpty())
                    throw invalid(node.getName(), "无需表单的节点仍配置了字段权限，请清理失效引用后发布");
            } else if (BpmNodeFormBinding.Source.FLOW_FORM.name().equals(kind)) {
                long id;
                try {
                    id = Long.parseLong(source.path("formId").asText());
                } catch (NumberFormatException e) {
                    throw invalid(node.getName(), "请选择有效流程表单");
                }
                var form = formService.getForm(id);
                if (form == null) throw invalid(node.getName(), "流程表单不存在或已删除");
                resolved.put("formId", String.valueOf(id));
                resolved.put("formName", form.getName());
                resolved.put("formConf", form.getConf());
                resolved.set(
                        "formFields",
                        JsonUtils.parseObject(
                                JsonUtils.toJsonString(form.getFields()), JsonNode.class));
                node.setFormKey(String.valueOf(id));
                rejectConflictingBusiness(node);
                var permissions = BpmnModelUtils.parseFormFieldsPermission(bpmn, node.getId());
                var fields =
                        com.richuang.os.module.bpm.framework.flowable.core.util.BpmNodeFormUtils
                                .fields(form);
                if (permissions != null && !fields.keySet().containsAll(permissions.keySet()))
                    throw invalid(node.getName(), "字段权限仍引用其他表单的字段，请调整节点字段权限后发布");
            } else if (BpmNodeFormBinding.Source.APPLICATION_RESOURCE.name().equals(kind)) {
                var configuration = source.path("configuration");
                if (!configuration.isObject()) throw invalid(node.getName(), "请选择应用已发布的业务表单");
                rejectConflictingBusiness(node);
                attribute(
                        node,
                        BpmBusinessTaskBinding.NAMESPACE,
                        "business",
                        BpmBusinessTaskBinding.HANDLER_ATTRIBUTE,
                        "nocode");
                attribute(
                        node,
                        BpmBusinessTaskBinding.NAMESPACE,
                        "business",
                        BpmBusinessTaskBinding.CONFIGURATION_ATTRIBUTE,
                        configuration.toString());
                node.setFormKey(null);
            } else if (BpmNodeFormBinding.Source.SYSTEM_ROUTE.name().equals(kind)) {
                // 旧业务流程继续沿用原路由；新节点路由不能凭任意路径获得任务提交能力。
                if (!BpmNodeFormBinding.Mode.INHERIT.name().equals(mode))
                    throw invalid(node.getName(), "系统业务路由尚未注册节点办理接口，请沿用发起表单或选择已接入的表单");
                rejectConflictingBusiness(node);
                node.setFormKey(null);
            } else throw invalid(node.getName(), "表单来源尚未支持，请保留原配置并检查来源");
            attribute(
                    node,
                    BpmNodeFormBinding.NAMESPACE,
                    "nodeForm",
                    BpmNodeFormBinding.RESOLVED,
                    resolved.toString());
            changed = true;
        }
        return changed ? BpmnModelUtils.getBpmnXml(bpmn).getBytes(StandardCharsets.UTF_8) : xml;
    }

    @Override
    public BpmFormDO getDeployedForm(FlowElement node) {
        return com.richuang.os.module.bpm.framework.flowable.core.util.BpmNodeFormUtils.form(node);
    }

    private JsonNode inherited(BpmModelMetaInfoVO model) {
        var source = JsonUtils.parseObject("{}", ObjectNode.class);
        if (model == null) throw invalid("发起节点", "缺少发起配置");
        if (BpmModelFormTypeEnum.NONE.getType().equals(model.getFormType())) {
            source.put("kind", BpmNodeFormBinding.Source.NONE.name());
        } else if (BpmModelFormTypeEnum.NORMAL.getType().equals(model.getFormType())) {
            source.put("kind", BpmNodeFormBinding.Source.FLOW_FORM.name());
            if (model.getFormId() != null) source.put("formId", String.valueOf(model.getFormId()));
        } else if (BpmModelFormTypeEnum.CUSTOM.getType().equals(model.getFormType())) {
            source.put("kind", BpmNodeFormBinding.Source.SYSTEM_ROUTE.name());
            source.put("createPath", model.getFormCustomCreatePath());
            source.put("viewPath", model.getFormCustomViewPath());
        }
        return source;
    }

    private void rejectConflictingBusiness(UserTask node) {
        if (node.getAttributeValue(
                                BpmBusinessTaskBinding.NAMESPACE,
                                BpmBusinessTaskBinding.HANDLER_ATTRIBUTE)
                        != null
                || node.getAttributeValue(
                                BpmBusinessTaskBinding.NAMESPACE,
                                BpmBusinessTaskBinding.CONFIGURATION_ATTRIBUTE)
                        != null) throw invalid(node.getName(), "同时存在旧业务绑定，请通过节点配置明确替换，不能隐式覆盖");
    }

    private ObjectNode object(String raw, String name) {
        try {
            var value = JsonUtils.parseObject(raw, JsonNode.class);
            if (!(value instanceof ObjectNode)) throw invalid(name, "表单配置必须是对象");
            return (ObjectNode) value;
        } catch (RuntimeException e) {
            throw invalid(name, "表单配置格式损坏");
        }
    }

    /** 历史版本恢复的 XML 已包含派生绑定；仅在原配置完全一致时去除后重新解析。 */
    private void removePreviouslyResolvedBusiness(UserTask node) {
        String raw =
                node.getAttributeValue(BpmNodeFormBinding.NAMESPACE, BpmNodeFormBinding.RESOLVED);
        if (raw == null) return;
        var previous = object(raw, node.getName());
        var source = previous.path("source");
        String handler =
                node.getAttributeValue(
                        BpmBusinessTaskBinding.NAMESPACE, BpmBusinessTaskBinding.HANDLER_ATTRIBUTE);
        String config =
                node.getAttributeValue(
                        BpmBusinessTaskBinding.NAMESPACE,
                        BpmBusinessTaskBinding.CONFIGURATION_ATTRIBUTE);
        if (BpmNodeFormBinding.Source.APPLICATION_RESOURCE
                        .name()
                        .equals(source.path("kind").asText())
                && "nocode".equals(handler)
                && config != null
                && source.path("configuration").equals(object(config, node.getName()))) {
            for (String name :
                    new String[] {
                        BpmBusinessTaskBinding.HANDLER_ATTRIBUTE,
                        BpmBusinessTaskBinding.CONFIGURATION_ATTRIBUTE
                    }) {
                var attributes = node.getAttributes().get(name);
                if (attributes != null)
                    attributes.removeIf(
                            value -> BpmBusinessTaskBinding.NAMESPACE.equals(value.getNamespace()));
            }
        }
    }

    private RuntimeException invalid(String name, String reason) {
        return exception(MODEL_NODE_FORM_INVALID, name, reason);
    }

    private void attribute(
            FlowElement node, String namespace, String prefix, String name, String value) {
        var attributes = node.getAttributes().get(name);
        if (attributes != null) attributes.removeIf(item -> namespace.equals(item.getNamespace()));
        var attribute = new ExtensionAttribute();
        attribute.setNamespace(namespace);
        attribute.setNamespacePrefix(prefix);
        attribute.setName(name);
        attribute.setValue(value);
        node.addAttribute(attribute);
    }
}
