package com.richuang.os.nocode.runtime.service.taskcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.richuang.os.nocode.api.TaskCenter.NodeInput;
import com.richuang.os.nocode.runtime.dal.dataobject.TaskInstanceDO;
import com.richuang.os.nocode.runtime.dal.dataobject.TaskTemplateVersionDO;
import com.richuang.os.nocode.runtime.dal.mapper.TaskCenterMapper;

import java.util.*;

/** 实例展示共用编排顺序；只重排节点，不改执行依赖、状态或可见范围。 */
public final class TaskInstanceOrdering {
    private TaskInstanceOrdering() {}

    /** 显示顺序与实例配置一起持久化，不依赖创建时间、执行历史或额外数据库列。 */
    static Integer displayOrder(ObjectMapper json, TaskInstanceDO task) {
        if (task.getConfigJson() == null) return null;
        try {
            JsonNode order = json.readTree(task.getConfigJson()).path("displayOrder");
            return order.isIntegralNumber() && order.canConvertToInt() && order.intValue() >= 0
                    ? order.intValue()
                    : null;
        } catch (Exception invalidHistory) {
            return null;
        }
    }

    /** 服务端生成排序元数据；领取、分工只改业务配置时也必须保留此值。 */
    static String config(ObjectMapper json, NodeInput node, Integer displayOrder) {
        try {
            ObjectNode value = json.valueToTree(node);
            if (displayOrder != null) value.put("displayOrder", displayOrder);
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("任务编排配置序列化失败", failure);
        }
    }

    public static List<TaskInstanceDO> instance(
            TaskCenterMapper store, ObjectMapper json, String rootId) {
        List<TaskInstanceDO> nodes = store.instance(rootId);
        if (nodes.size() < 2) return nodes;
        TaskInstanceDO root =
                nodes.stream().filter(node -> node.getId().equals(rootId)).findFirst().orElse(null);
        Map<String, Integer> templateOrder = new HashMap<>();
        Map<String, Integer> storedOrder = new HashMap<>();
        nodes.forEach(node -> storedOrder.put(node.getId(), displayOrder(json, node)));
        if (root != null
                && root.getTemplateId() != null
                && root.getTemplateVersion() != null
                && storedOrder.values().stream().anyMatch(Objects::isNull)) {
            TaskTemplateVersionDO source =
                    store.version(root.getTemplateId(), root.getTemplateVersion());
            if (source != null) {
                List<String> ids = ids(json, source.getNodesJson());
                for (int index = 0; index < ids.size(); index++)
                    templateOrder.put(ids.get(index), index);
            }
        }
        // 旧模板没有实例序号时从固定版本恢复并行顺序，不读取模板草稿或改变存量数据。
        List<TaskInstanceDO> baseline = new ArrayList<>(nodes);
        baseline.sort(
                Comparator.comparingInt(
                                (TaskInstanceDO node) ->
                                        templateOrder.getOrDefault(
                                                node.getTemplateNodeId(), Integer.MAX_VALUE))
                        .thenComparing(
                                TaskInstanceDO::getCreateTime,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TaskInstanceDO::getId));
        Map<String, Integer> legacyPositions = new HashMap<>();
        for (int index = 0; index < baseline.size(); index++)
            legacyPositions.put(baseline.get(index).getId(), index);
        baseline.sort(
                Comparator.comparingInt(
                        node ->
                                storedOrder.get(node.getId()) == null
                                        ? legacyPositions.get(node.getId())
                                        : storedOrder.get(node.getId())));
        Map<String, List<String>> predecessors = new HashMap<>();
        for (TaskInstanceDO node : baseline) {
            try {
                NodeInput config = json.readValue(node.getConfigJson(), NodeInput.class);
                predecessors.put(
                        node.getId(),
                        config.predecessorIds() == null ? List.of() : config.predecessorIds());
            } catch (Exception invalidHistory) {
                // 排序兼容旧配置，业务配置是否可用仍由原有领域校验负责。
                predecessors.put(node.getId(), List.of());
            }
        }
        return ordered(baseline, predecessors);
    }

    private static List<String> ids(ObjectMapper json, String value) {
        if (value == null) return List.of();
        try {
            JsonNode nodes = json.readTree(value);
            if (!nodes.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            for (JsonNode node : nodes)
                if (node.path("id").isTextual()) result.add(node.path("id").asText());
            return result;
        } catch (Exception invalidHistory) {
            return List.of();
        }
    }

    /** 稳定拓扑仅整理兄弟节点；权限过滤应在完整顺序计算之后执行。 */
    static List<TaskInstanceDO> ordered(
            List<TaskInstanceDO> baseline, Map<String, List<String>> predecessors) {
        Set<String> ids = new HashSet<>();
        baseline.forEach(node -> ids.add(node.getId()));
        Map<String, List<TaskInstanceDO>> groups = new HashMap<>();
        for (TaskInstanceDO node : baseline) {
            String parent = ids.contains(node.getParentId()) ? node.getParentId() : null;
            groups.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(node);
        }
        List<TaskInstanceDO> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visit(null, groups, predecessors, result, visited);
        // 异常旧层级不能被排序吞掉，原有详情校验仍会提示结构问题。
        for (TaskInstanceDO node : baseline) if (!visited.contains(node.getId())) result.add(node);
        return List.copyOf(result);
    }

    private static void visit(
            String parent,
            Map<String, List<TaskInstanceDO>> groups,
            Map<String, List<String>> predecessors,
            List<TaskInstanceDO> result,
            Set<String> visited) {
        List<TaskInstanceDO> pending = new ArrayList<>(groups.getOrDefault(parent, List.of()));
        while (!pending.isEmpty()) {
            Set<String> remaining = new HashSet<>();
            pending.forEach(node -> remaining.add(node.getId()));
            TaskInstanceDO next =
                    pending.stream()
                            .filter(
                                    node ->
                                            predecessors
                                                    .getOrDefault(node.getId(), List.of())
                                                    .stream()
                                                    .noneMatch(remaining::contains))
                            .findFirst()
                            .orElse(pending.getFirst());
            pending.remove(next);
            if (!visited.add(next.getId())) continue;
            result.add(next);
            visit(next.getId(), groups, predecessors, result, visited);
        }
    }
}
