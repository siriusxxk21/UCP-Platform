package com.lingan.ucp.nocode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表单资源 JSON 的节点级读写：迁移只动节点 presentation 里的 fill 与 selection.defaultValue，其余原样保留。
 *
 * <p>节点键：主表单节点为 {@code main:<节点ID>}，明细表单节点为 {@code detail:<明细ID>:<节点ID>}。
 */
final class ObjectRuleMigrationForms {
    static final String FORM = "FORM";

    private ObjectRuleMigrationForms() {}

    static String mainKey(String nodeId) {
        return "main:" + nodeId;
    }

    static String detailKey(String detailId, String nodeId) {
        return "detail:" + detailId + ":" + nodeId;
    }

    /** 明细节点键中的明细 ID；主表单节点返回 null。 */
    static String detailOf(String nodeKey) {
        if (!nodeKey.startsWith("detail:")) return null;
        return nodeKey.substring("detail:".length(), nodeKey.lastIndexOf(':'));
    }

    /** 全部字段节点（含 children 与 detailNodes），值是表单树中的原节点，可原地修改。 */
    static Map<String, ObjectNode> fieldNodes(JsonNode form) {
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        collect(form.path("nodes"), null, result);
        var details = form.path("detailNodes");
        if (details.isObject())
            details.fields().forEachRemaining(e -> collect(e.getValue(), e.getKey(), result));
        return result;
    }

    private static void collect(JsonNode nodes, String detailId, Map<String, ObjectNode> result) {
        if (!nodes.isArray()) return;
        for (var node : nodes) {
            if (!(node instanceof ObjectNode object)) continue;
            String id = text(object, "id");
            if (id != null && text(object, "fieldId") != null)
                result.put(detailId == null ? mainKey(id) : detailKey(detailId, id), object);
            collect(object.path("children"), detailId, result);
        }
    }

    static JsonNode fill(JsonNode node) {
        var fill = node.path("presentation").path("fill");
        return fill.isObject() ? fill : null;
    }

    static JsonNode selectionDefault(JsonNode node) {
        var value = node.path("presentation").path("selection").path("defaultValue");
        return value.isMissingNode() || value.isNull() ? null : value;
    }

    /** 去掉节点上的关联带入；返回是否改动。 */
    static boolean stripFill(ObjectNode node) {
        if (fill(node) == null) return false;
        ((ObjectNode) node.get("presentation")).remove("fill");
        return true;
    }

    /** 去掉节点上的本表单默认值；返回是否改动。 */
    static boolean stripSelectionDefault(ObjectNode node) {
        if (selectionDefault(node) == null) return false;
        ((ObjectNode) node.get("presentation").get("selection")).remove("defaultValue");
        return true;
    }

    /** 用迁移前的节点 JSON 原样替换当前节点；返回是否改动。 */
    static boolean restore(ObjectNode node, JsonNode before) {
        if (node.equals(before)) return false;
        node.removeAll();
        node.setAll((ObjectNode) before.deepCopy());
        return true;
    }

    static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isTextual() ? value.asText() : value.isNumber() ? value.asText() : null;
    }
}
