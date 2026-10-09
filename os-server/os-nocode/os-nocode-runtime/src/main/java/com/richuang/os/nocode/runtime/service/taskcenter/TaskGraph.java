package com.richuang.os.nocode.runtime.service.taskcenter;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.api.TaskWorkEntries;
import com.richuang.os.nocode.api.TaskWorkTimes;

import java.util.*;

/** 任务树和执行依赖独立验证；开始/完成分别建图，避免父开始与子完成组合造成死锁。 */
public final class TaskGraph {
    private TaskGraph() {}

    public static List<NodeInput> normalize(List<NodeInput> input, long actor) {
        return normalize(input, actor, false);
    }

    public static List<NodeInput> template(List<NodeInput> input, long actor) {
        return normalize(input, actor, true);
    }

    private static List<NodeInput> normalize(List<NodeInput> input, long actor, boolean template) {
        if (input == null || input.isEmpty() || input.size() > 200)
            throw invalid("实例必须包含 1 至 200 个任务");
        List<NodeInput> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (NodeInput node : input) {
            if (node == null || node.id() == null || node.id().isBlank() || !ids.add(node.id()))
                throw invalid("节点标识不能为空或重复");
            String title = text(node.title(), "任务名称", 200, true);
            Integer effectiveWorkMinutes = node.effectiveWorkMinutes();
            if (node.workTotalMode() != null && blank(node.parentId()) != null)
                throw invalid("总工时计算方式只能配置在总任务");
            if (node.workTotalMode() == TaskWorkTimes.TotalMode.AUTO)
                effectiveWorkMinutes = TaskWorkBudgets.total(budgetEntries(node, input));
            if (effectiveWorkMinutes != null) {
                if (effectiveWorkMinutes < 0 || effectiveWorkMinutes > 599999)
                    throw invalid("有效工作时长须为 0 至 599999 的整数分钟");
                if (effectiveWorkMinutes == 0) effectiveWorkMinutes = null;
                if (effectiveWorkMinutes != null && blank(node.parentId()) != null)
                    throw invalid("有效工作时长只能配置在总任务，不能分配给子任务");
            }
            Schedule schedule =
                    node.schedule() == null
                            ? new Schedule(
                                    node.assignmentMode() == null
                                            ? TimeMode.T0
                                            : TimeMode.UNSCHEDULED,
                                    null,
                                    0,
                                    0)
                            : node.schedule();
            if (schedule.mode() == null
                    || schedule.offsetDays() < 0
                    || schedule.offsetDays() > 36500
                    || schedule.durationDays() < 0
                    || schedule.durationDays() > 36500) throw invalid("时间偏移和持续天数须为 0 至 36500");
            if (schedule.mode() == TimeMode.FIXED
                    && schedule.fixedStart() == null
                    && schedule.fixedEnd() == null) throw invalid("固定模式请设置开始或截止时间");
            if (schedule.mode() == TimeMode.FIXED
                    && schedule.fixedStart() != null
                    && schedule.fixedEnd() != null
                    && schedule.fixedEnd().isBefore(schedule.fixedStart()))
                throw invalid("截止时间不能早于开始时间");
            List<Long> candidates =
                    node.candidateUserIds() == null
                            ? null
                            : new ArrayList<>(node.candidateUserIds());
            if (candidates != null
                    && (candidates.size() > 200
                            || candidates.stream().anyMatch(id -> id == null || id <= 0)
                            || new HashSet<>(candidates).size() != candidates.size()))
                throw invalid("可领取成员无效、重复或超过200人");
            if (node.assignmentMode() == AssignmentMode.ASSIGNED && node.assigneeId() == null)
                throw invalid("指定负责人时请选择成员");
            if (node.assignmentMode() != null
                    && node.assignmentMode() != AssignmentMode.ASSIGNED
                    && node.assignmentMode() != AssignmentMode.FOLLOW_ROOT
                    && node.assigneeId() != null) throw invalid("未分配或开放领取任务不能预设负责人");
            if (node.assignmentMode() == AssignmentMode.FOLLOW_ROOT
                    && (blank(node.parentId()) == null || template && node.assigneeId() != null))
                throw invalid("随总任务仅用于子任务；模板中不能预设已承接负责人");
            if (node.acceptorId() != null) {
                if (node.acceptorId() <= 0 || blank(node.parentId()) != null)
                    throw invalid("仅总任务可以配置有效验收人");
                Long assignee =
                        node.assigneeId() == null && node.assignmentMode() == null && !template
                                ? Long.valueOf(actor)
                                : node.assigneeId();
                if (Objects.equals(node.acceptorId(), assignee)) throw invalid("负责人和验收人不能是同一人");
            }
            if (node.assignmentMode() != AssignmentMode.OPEN
                    && candidates != null
                    && !candidates.isEmpty()) throw invalid("只有开放领取任务可以限制领取成员");
            if (node.predecessorIds() != null
                    && node.predecessorIds().stream().anyMatch(id -> id == null || id.isBlank()))
                throw invalid("前置任务标识不能为空");
            List<String> predecessors =
                    node.predecessorIds() == null ? List.of() : List.copyOf(node.predecessorIds());
            if (new HashSet<>(predecessors).size() != predecessors.size())
                throw invalid("前置任务不能重复");
            if (schedule.mode() == TimeMode.PREDECESSOR && predecessors.isEmpty())
                throw invalid("前置完成时间模式需要选择前置任务");
            Sharing sharing =
                    node.sharing() == null
                            ? new Sharing(DataMode.INDEPENDENT, null, List.of())
                            : node.sharing();
            if (sharing.mode() == null) throw invalid("请选择数据模式");
            if (sharing.writableFieldIds() != null
                    && sharing.writableFieldIds().stream()
                            .anyMatch(id -> id == null || id.isBlank()))
                throw invalid("共享字段标识不能为空");
            sharing =
                    new Sharing(
                            sharing.mode(),
                            sharing.sourceNodeId(),
                            sharing.writableFieldIds() == null
                                    ? List.of()
                                    : List.copyOf(sharing.writableFieldIds()));
            if (sharing.mode() == DataMode.INDEPENDENT
                    && (sharing.sourceNodeId() != null || !sharing.writableFieldIds().isEmpty()))
                throw invalid("独立数据模式不能配置共享来源或字段");
            if (sharing.mode() == DataMode.SHARED && node.binding() != null)
                throw invalid("共享节点沿用来源表单，不能另绑业务表单");
            if (node.entries() != null && node.entries().stream().anyMatch(Objects::isNull))
                throw invalid("任务办理入口配置不能为空");
            // 显式数据授权不能混入旧实例的整树参与者可见协议；历史请求的默认人员语义保持不变。
            if (node.dataPolicy() != null && node.assignmentMode() == null)
                throw invalid("总任务启用统一数据权限时，请明确选择人员安排");
            if (node.dataPolicy() != null
                    && (blank(node.parentId()) != null
                            || node.dataPolicy().version() != 1
                            || node.dataPolicy().business() == null
                            || node.dataPolicy().feedback() == null))
                throw invalid("数据权限只能配置在总任务，且必须选择业务和反馈数据范围");
            result.add(
                    new NodeInput(
                            node.id(),
                            blank(node.parentId()),
                            title,
                            text(node.description(), "任务说明", 4000, false),
                            node.assigneeId() == null && node.assignmentMode() == null && !template
                                    ? Long.valueOf(actor)
                                    : node.assigneeId(),
                            node.urgency() == null ? Urgency.NORMAL : node.urgency(),
                            node.priority() == null ? Priority.MEDIUM : node.priority(),
                            schedule,
                            predecessors,
                            node.binding(),
                            sharing,
                            node.entries() == null || node.entries().isEmpty()
                                    ? null
                                    : List.copyOf(node.entries()),
                            node.assignmentMode(),
                            candidates == null ? null : List.copyOf(candidates),
                            node.dataPolicy(),
                            node.acceptorId(),
                            effectiveWorkMinutes,
                            node.workTotalMode()));
        }
        Map<String, NodeInput> nodes = index(result);
        Map<String, List<String>> waits = new LinkedHashMap<>();
        for (NodeInput node : result) {
            if (node.parentId() != null
                    && (!ids.contains(node.parentId()) || node.id().equals(node.parentId())))
                throw invalid("父任务不存在或指向自身");
            if (node.predecessorIds().stream()
                    .anyMatch(id -> !ids.contains(id) || id.equals(node.id())))
                throw invalid("前置任务必须为当前实例的其他任务");
            List<String> startWaits = new ArrayList<>();
            for (String predecessor : node.predecessorIds()) startWaits.add("END:" + predecessor);
            if (node.parentId() != null) startWaits.add("START:" + node.parentId());
            waits.put("START:" + node.id(), startWaits);
            waits.put("END:" + node.id(), new ArrayList<>(List.of("START:" + node.id())));
        }
        for (NodeInput node : result)
            if (node.parentId() != null)
                waits.get("END:" + node.parentId()).add("END:" + node.id());
        Set<String> done = new HashSet<>();
        for (String id : waits.keySet()) visit(id, waits, new HashSet<>(), done);
        if (!template && result.stream().filter(n -> n.parentId() == null).count() != 1)
            throw invalid("实例须有且只有一个根任务");
        for (NodeInput node : result) {
            if (node.sharing().mode() == DataMode.SHARED) {
                String source = node.sharing().sourceNodeId();
                if (source == null
                        || !nodes.containsKey(source)
                        || !ancestors(node.id(), nodes, new HashSet<>()).contains(source))
                    throw invalid("共享来源必须是同实例内的前序任务");
            }
        }
        return List.copyOf(result);
    }

    /** 旧协议可在子节点声明独立办理项；只合计声明项，统一授权的继承项不能重复预算。 */
    private static List<TaskWorkEntries.Config> budgetEntries(
            NodeInput root, List<NodeInput> nodes) {
        if (root.dataPolicy() != null) return root.entries();
        Set<String> descendants = new HashSet<>(Set.of(root.id()));
        boolean changed;
        do {
            changed = false;
            for (NodeInput node : nodes) {
                if (node != null && descendants.contains(node.parentId()))
                    changed |= descendants.add(node.id());
            }
        } while (changed);
        List<TaskWorkEntries.Config> entries = new ArrayList<>();
        for (NodeInput node : nodes) {
            if (node != null && descendants.contains(node.id()) && node.entries() != null)
                entries.addAll(node.entries());
        }
        return entries;
    }

    private static void visit(
            String id, Map<String, List<String>> waits, Set<String> visiting, Set<String> done) {
        if (done.contains(id)) return;
        if (!visiting.add(id)) throw invalid("任务依赖存在环或父子完成死锁");
        for (String next : waits.get(id)) visit(next, waits, visiting, done);
        visiting.remove(id);
        done.add(id);
    }

    private static Set<String> ancestors(
            String id, Map<String, NodeInput> nodes, Set<String> result) {
        for (String predecessor : nodes.get(id).predecessorIds())
            if (result.add(predecessor)) ancestors(predecessor, nodes, result);
        return result;
    }

    public static Map<String, NodeInput> index(List<NodeInput> nodes) {
        Map<String, NodeInput> result = new LinkedHashMap<>();
        for (NodeInput node : nodes) result.put(node.id(), node);
        return result;
    }

    public static String text(String value, String name, int max, boolean required) {
        String normalized = value == null ? null : value.trim();
        if (required && (normalized == null || normalized.isEmpty())) throw invalid(name + "不能为空");
        if (normalized != null && normalized.length() > max) throw invalid(name + "过长");
        return normalized;
    }

    public static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
