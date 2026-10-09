package com.lingan.ucp.module.bpm.framework.flowable.core.util;

import static com.lingan.ucp.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.lingan.ucp.module.bpm.enums.ErrorCodeConstants.MODEL_NODE_FORM_INVALID;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.framework.common.util.json.JsonUtils;
import com.lingan.ucp.module.bpm.api.task.BpmNodeFormBinding;
import com.lingan.ucp.module.bpm.dal.dataobject.definition.BpmFormDO;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** 已部署节点的表单快照读取，不查询可变的表单设计表。 */
public final class BpmNodeFormUtils {
    public static JsonNode resolved(FlowElement node) {
        if (node == null) return null;
        String raw =
                node.getAttributeValue(BpmNodeFormBinding.NAMESPACE, BpmNodeFormBinding.RESOLVED);
        return raw == null ? null : JsonUtils.parseObject(raw, JsonNode.class);
    }

    public static BpmFormDO form(FlowElement node) {
        var value = resolved(node);
        if (value == null
                || !BpmNodeFormBinding.Source.FLOW_FORM
                        .name()
                        .equals(value.path("source").path("kind").asText())) return null;
        var result = new BpmFormDO();
        result.setId(Long.valueOf(value.path("formId").asText()));
        result.setName(value.path("formName").asText());
        result.setConf(value.path("formConf").asText());
        var fields = new ArrayList<String>();
        value.path("formFields").forEach(field -> fields.add(field.asText()));
        result.setFields(fields);
        return result;
    }

    public static Map<String, JsonNode> fields(BpmFormDO form) {
        var fields = new LinkedHashMap<String, JsonNode>();
        if (form.getFields() != null)
            for (String field : form.getFields())
                collect(JsonUtils.parseObject(field, JsonNode.class), fields);
        return fields;
    }

    private static void collect(JsonNode value, Map<String, JsonNode> fields) {
        if (value == null) return;
        if (value.isArray()) {
            value.forEach(item -> collect(item, fields));
            return;
        }
        if (!value.isObject()) return;
        if (value.path("field").isTextual()) fields.put(value.path("field").asText(), value);
        collect(value.get("children"), fields);
    }

    /** 新独立表单只允许提交其可写字段；不改变旧流程的变量协议。 */
    public static void validateSubmission(
            BpmnModel model, String nodeId, Map<String, Object> variables) {
        var node = BpmnModelUtils.getFlowElementById(model, nodeId);
        var binding = resolved(node);
        if (binding != null
                && BpmNodeFormBinding.Source.NONE
                        .name()
                        .equals(binding.path("source").path("kind").asText())) {
            if (variables != null && !variables.isEmpty())
                throw exception(MODEL_NODE_FORM_INVALID, node.getName(), "无需表单的节点不能提交额外业务字段");
            return;
        }
        if (binding == null
                || !BpmNodeFormBinding.Mode.OVERRIDE.name().equals(binding.path("mode").asText()))
            return;
        var form = form(node);
        if (form == null) return;
        var fields = fields(form);
        var permissions = BpmnModelUtils.parseFormFieldsPermission(model, nodeId);
        var submitted = variables == null ? Map.<String, Object>of() : variables;
        for (String key : submitted.keySet()) {
            if (!fields.containsKey(key)
                    || permissions != null
                            && permissions.containsKey(key)
                            && !"2".equals(permissions.get(key)))
                throw exception(MODEL_NODE_FORM_INVALID, node.getName(), "不能提交未配置或不可写字段：" + key);
        }
        for (var entry : fields.entrySet()) {
            if (permissions != null
                    && permissions.containsKey(entry.getKey())
                    && !"2".equals(permissions.get(entry.getKey()))) continue;
            boolean required = entry.getValue().path("props").path("required").asBoolean(false);
            for (var rule : entry.getValue().path("validate"))
                required |= rule.path("required").asBoolean(false);
            Object value = submitted.get(entry.getKey());
            if (required
                    && (value == null
                            || value instanceof String text && text.isBlank()
                            || value instanceof java.util.Collection<?> values && values.isEmpty()))
                throw exception(
                        MODEL_NODE_FORM_INVALID, node.getName(), "请填写必填字段：" + entry.getKey());
        }
    }

    private BpmNodeFormUtils() {}
}
