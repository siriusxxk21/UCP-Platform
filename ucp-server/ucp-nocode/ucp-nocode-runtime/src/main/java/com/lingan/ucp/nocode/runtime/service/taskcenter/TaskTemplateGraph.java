package com.lingan.ucp.nocode.runtime.service.taskcenter;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.TaskCenter.DataMode;
import com.lingan.ucp.nocode.api.TaskCenter.NodeInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 模板的总任务与子节点分开存储，验证时组合成同一任务图；旧模板不推断总任务。 */
public final class TaskTemplateGraph {
    private TaskTemplateGraph() {}

    public record Definition(NodeInput task, List<NodeInput> nodes, List<NodeInput> all) {}

    public static Definition normalize(NodeInput root, List<NodeInput> children, long actor) {
        if (root == null) {
            List<NodeInput> nodes = TaskGraph.template(children, actor);
            if (nodes.stream().anyMatch(n -> n.dataPolicy() != null))
                throw invalid("总任务数据权限必须配置在独立的模板总任务中");
            if (nodes.stream().anyMatch(n -> n.acceptorId() != null))
                throw invalid("验收人必须配置在独立的模板总任务中");
            if (nodes.stream().anyMatch(n -> n.effectiveWorkMinutes() != null))
                throw invalid("有效工作时长必须配置在独立的模板总任务中");
            return new Definition(null, nodes, nodes);
        }
        if (TaskGraph.blank(root.parentId()) != null
                || root.predecessorIds() != null && !root.predecessorIds().isEmpty())
            throw invalid("模板总任务不能设置上级或前置任务");
        List<NodeInput> graph = new ArrayList<>();
        graph.add(root);
        for (NodeInput child : children == null ? List.<NodeInput>of() : children) {
            if (child == null) throw invalid("模板子任务不能为空");
            if (child.dataPolicy() != null) throw invalid("子任务统一继承总任务数据权限，不能单独配置");
            if (root.dataPolicy() != null
                    && (child.binding() != null
                            || child.entries() != null && !child.entries().isEmpty()
                            || child.sharing() != null
                                    && child.sharing().mode() != DataMode.INDEPENDENT))
                throw invalid("子任务统一使用总任务的业务数据和反馈资源，不能单独配置");
            graph.add(
                    parent(
                            child,
                            TaskGraph.blank(child.parentId()) == null
                                    ? root.id()
                                    : child.parentId()));
        }
        List<NodeInput> normalized = TaskGraph.template(graph, actor);
        NodeInput task = normalized.getFirst();
        List<NodeInput> nodes =
                normalized.stream()
                        .skip(1)
                        .map(n -> Objects.equals(task.id(), n.parentId()) ? parent(n, null) : n)
                        .toList();
        return new Definition(task, nodes, normalized);
    }

    private static NodeInput parent(NodeInput node, String parentId) {
        return new NodeInput(
                node.id(),
                parentId,
                node.title(),
                node.description(),
                node.assigneeId(),
                node.urgency(),
                node.priority(),
                node.schedule(),
                node.predecessorIds(),
                node.binding(),
                node.sharing(),
                node.entries(),
                node.assignmentMode(),
                node.candidateUserIds(),
                node.dataPolicy(),
                node.acceptorId(),
                node.effectiveWorkMinutes(),
                node.workTotalMode());
    }
}
