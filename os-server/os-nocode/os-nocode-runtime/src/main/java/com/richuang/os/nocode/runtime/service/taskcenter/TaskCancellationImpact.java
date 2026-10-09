package com.richuang.os.nocode.runtime.service.taskcenter;

import com.richuang.os.nocode.api.TaskCenter;
import com.richuang.os.nocode.api.TaskGuidance;

import java.util.*;

/** 取消只分析尚未结束的依赖后继，不将取消伪装成完成，也不修改执行图。 */
final class TaskCancellationImpact {
    private TaskCancellationImpact() {}

    static List<TaskGuidance.CancellationImpact> inspect(
            String taskId, List<TaskCenter.NodeInput> nodes, Set<String> unfinished) {
        Set<String> affected = new LinkedHashSet<>(List.of(taskId));
        boolean changed;
        do {
            changed = false;
            for (TaskCenter.NodeInput node : nodes) {
                if (unfinished.contains(node.id())
                        && !affected.contains(node.id())
                        && (node.predecessorIds().stream().anyMatch(affected::contains)
                                || node.parentId() != null
                                        && !node.parentId().equals(taskId)
                                        && affected.contains(node.parentId())))
                    changed |= affected.add(node.id());
            }
        } while (changed);
        return nodes.stream()
                .filter(node -> !node.id().equals(taskId) && affected.contains(node.id()))
                .map(
                        node ->
                                new TaskGuidance.CancellationImpact(
                                        node.id(),
                                        node.title(),
                                        node.predecessorIds().contains(taskId),
                                        node.predecessorIds().contains(taskId)
                                                ? "直接前置被取消后无法满足开始条件，请调整依赖"
                                                : "上游前置或上级任务被阻塞，此任务将无法开始，请调整依赖"))
                .toList();
    }
}
