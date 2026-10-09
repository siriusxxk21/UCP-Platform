package com.lingan.ucp.nocode.runtime.service.taskcenter;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.runtime.dal.dataobject.TaskInstanceDO;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

/** 排期使用自然日；自动父节点只汇总子级，不作为子级的日期锚点，避免父子计算循环。 */
public final class TaskSchedules {
    private TaskSchedules() {}

    /** endComplete 区分“已知部分子级的最晚结束”和“全体子级均已排定结束”。 */
    public record Dates(
            LocalDateTime start, LocalDateTime end, ScheduleSummary summary, boolean endComplete) {}

    public static void calculate(
            List<TaskInstanceDO> tasks, Function<TaskInstanceDO, NodeInput> config) {
        Engine engine = new Engine(tasks, config, true);
        Map<String, Dates> dates = engine.all();
        for (TaskInstanceDO task : tasks) {
            if (!mutable(task)) continue;
            Dates value = dates.get(task.getId());
            // 历史未排期父节点只读汇总，不在写操作中擅自改成自动排期。
            boolean unscheduled = config.apply(task).schedule().mode() == TimeMode.UNSCHEDULED;
            task.setExpectedStart(unscheduled ? null : value.start());
            task.setExpectedEnd(unscheduled ? null : value.end());
        }
    }

    /** 查询投影不回填数据库；历史未排期父节点也可以显示已有下级日期。 */
    public static Map<String, Dates> display(
            List<TaskInstanceDO> tasks, Function<TaskInstanceDO, NodeInput> config) {
        return new Engine(tasks, config, false).all();
    }

    public static SchedulePreview preview(
            List<TaskInstanceDO> tasks, Function<TaskInstanceDO, NodeInput> config) {
        Map<String, Dates> dates = new Engine(tasks, config, true).all();
        List<SchedulePreviewNode> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (TaskInstanceDO task : tasks) {
            Dates value = dates.get(task.getId());
            rows.add(
                    new SchedulePreviewNode(
                            task.getId(),
                            task.getTitle(),
                            value.start(),
                            value.end(),
                            value.summary().partial(),
                            value.summary().warnings()));
            value.summary()
                    .warnings()
                    .forEach(warning -> warnings.add(task.getTitle() + "：" + warning));
        }
        return new SchedulePreview(List.copyOf(rows), List.copyOf(warnings));
    }

    private static boolean mutable(TaskInstanceDO task) {
        return State.PENDING.name().equals(task.getStatus())
                && task.getActualStart() == null
                && task.getActualEnd() == null;
    }

    private static final class Engine {
        private final Map<String, TaskInstanceDO> tasks = new LinkedHashMap<>();
        private final Map<String, NodeInput> configs = new HashMap<>();
        private final Map<String, List<TaskInstanceDO>> children = new HashMap<>();
        private final Map<String, Dates> resolved = new LinkedHashMap<>();
        private final Set<String> visiting = new HashSet<>();
        private final boolean recalculate;

        private Engine(
                List<TaskInstanceDO> nodes,
                Function<TaskInstanceDO, NodeInput> config,
                boolean recalculate) {
            this.recalculate = recalculate;
            for (TaskInstanceDO task : nodes) {
                tasks.put(task.getId(), task);
                configs.put(task.getId(), config.apply(task));
                if (task.getParentId() != null && !State.CANCELLED.name().equals(task.getStatus()))
                    children.computeIfAbsent(task.getParentId(), ignored -> new ArrayList<>())
                            .add(task);
            }
        }

        private Map<String, Dates> all() {
            tasks.values().forEach(this::resolve);
            return resolved;
        }

        private Dates resolve(TaskInstanceDO task) {
            Dates cached = resolved.get(task.getId());
            if (cached != null) return cached;
            if (!visiting.add(task.getId())) throw invalid("任务排期存在循环依赖");
            NodeInput node = configs.get(task.getId());
            Schedule schedule = node.schedule();
            List<TaskInstanceDO> direct = children.getOrDefault(task.getId(), List.of());
            boolean rollup =
                    !direct.isEmpty()
                            && (schedule.mode() == TimeMode.AUTO
                                    || schedule.mode() == TimeMode.UNSCHEDULED);
            ScheduleSource source =
                    schedule.mode() == TimeMode.AUTO
                            ? ScheduleSource.AUTO
                            : rollup
                                    ? ScheduleSource.ROLLUP
                                    : schedule.mode() == TimeMode.UNSCHEDULED
                                            ? ScheduleSource.UNSCHEDULED
                                            : ScheduleSource.EXPLICIT;
            LocalDateTime start = task.getExpectedStart();
            LocalDateTime end = task.getExpectedEnd();
            List<String> warnings = new ArrayList<>();
            boolean childPartial = false;
            boolean endComplete = end != null;
            boolean readOnlyMissingParent =
                    !recalculate
                            && schedule.mode() == TimeMode.UNSCHEDULED
                            && start == null
                            && end == null;
            if (rollup && (mutable(task) || readOnlyMissingParent)) {
                start = null;
                end = null;
                endComplete = true;
                for (TaskInstanceDO child : direct) {
                    Dates value = resolve(child);
                    start = earlier(start, value.start());
                    end = later(end, value.end());
                    childPartial |= value.summary().partial();
                    endComplete &= value.endComplete();
                }
                if (childPartial) warnings.add("部分下级尚未排完整日期，当前显示已知范围");
            } else if (mutable(task) && recalculate) {
                start = null;
                end = null;
                switch (schedule.mode()) {
                    case UNSCHEDULED -> {}
                    case AUTO -> {
                        Set<String> predecessors = effectivePredecessors(task);
                        LocalDateTime anchor =
                                predecessors.isEmpty()
                                        ? plannedStart(task)
                                        : predecessorEnd(predecessors, true);
                        if (anchor == null)
                            warnings.add(
                                    predecessors.isEmpty() ? "尚未设置整体计划开始时间" : "前置任务尚未排定完整结束时间");
                        else {
                            start = anchor.plusDays(schedule.offsetDays());
                            end = start.plusDays(schedule.durationDays());
                        }
                    }
                    case FIXED -> {
                        start = schedule.fixedStart();
                        end = schedule.fixedEnd();
                        // 旧协议 durationDays=0 表示同日；新协议允许只填开始或截止。
                        if (end == null
                                && start != null
                                && (node.assignmentMode() == null || schedule.durationDays() > 0))
                            end = start.plusDays(schedule.durationDays());
                    }
                    case T0 -> {
                        start = task.getT0().plusDays(schedule.offsetDays());
                        end = start.plusDays(schedule.durationDays());
                    }
                    case PLAN_START -> {
                        if (task.getPlannedStart() == null) throw invalid("相对计划开始时间必须先设置整体任务的计划起点");
                        start = task.getPlannedStart().plusDays(schedule.offsetDays());
                        end = start.plusDays(schedule.durationDays());
                    }
                    case PREDECESSOR -> {
                        LocalDateTime anchor =
                                predecessorEnd(new LinkedHashSet<>(node.predecessorIds()), false);
                        if (anchor != null) {
                            start = anchor.plusDays(schedule.offsetDays());
                            end = start.plusDays(schedule.durationDays());
                        }
                    }
                }
                endComplete = end != null;
            }
            // 固定安排及已执行父任务保留原日期，只报告冲突，不自动挪动老板设定的截止。
            if (!rollup || !mutable(task)) {
                for (TaskInstanceDO child : direct) {
                    Dates value = resolve(child);
                    childPartial |= value.summary().partial();
                    if (end != null
                            && value.end() != null
                            && value.end().isAfter(end)
                            && !warnings.contains("下级预计完成已超出本任务原定截止时间"))
                        warnings.add("下级预计完成已超出本任务原定截止时间");
                }
            }
            if (schedule.mode() == TimeMode.FIXED) {
                Set<String> predecessors = effectivePredecessors(task);
                LocalDateTime limit =
                        predecessors.isEmpty() ? null : predecessorEnd(predecessors, true);
                if (limit != null
                        && (start != null && start.isBefore(limit)
                                || end != null && end.isBefore(limit)))
                    warnings.add("固定日期早于前置完成时间，请确认安排");
            }
            Dates value =
                    new Dates(
                            start,
                            end,
                            new ScheduleSummary(
                                    source,
                                    start == null || end == null || childPartial,
                                    List.copyOf(warnings)),
                            endComplete);
            visiting.remove(task.getId());
            resolved.put(task.getId(), value);
            return value;
        }

        private LocalDateTime plannedStart(TaskInstanceDO task) {
            if (task.getPlannedStart() != null) return task.getPlannedStart();
            TaskInstanceDO root = tasks.get(task.getRootId());
            return root == null ? null : root.getPlannedStart();
        }

        private Set<String> effectivePredecessors(TaskInstanceDO task) {
            Set<String> ids = new LinkedHashSet<>();
            Set<String> parents = new HashSet<>();
            TaskInstanceDO current = task;
            while (current != null && parents.add(current.getId())) {
                ids.addAll(configs.get(current.getId()).predecessorIds());
                current = tasks.get(current.getParentId());
            }
            return ids;
        }

        private LocalDateTime predecessorEnd(Set<String> ids, boolean complete) {
            if (ids.isEmpty()) return null;
            LocalDateTime latest = null;
            boolean known = true;
            for (String id : ids) {
                TaskInstanceDO predecessor = tasks.get(id);
                if (predecessor == null) throw invalid("前置任务不存在");
                LocalDateTime end;
                if (State.COMPLETED.name().equals(predecessor.getStatus()))
                    end = predecessor.getActualEnd();
                else {
                    Dates value = resolve(predecessor);
                    end = value.end();
                    // 旧 PREDECESSOR 不因历史父节点新增只读汇总而改变原有排期含义。
                    if (!complete
                            && mutable(predecessor)
                            && configs.get(id).schedule().mode() == TimeMode.UNSCHEDULED)
                        end = null;
                    if (complete && !value.endComplete()) known = false;
                }
                if (end == null) known = false;
                latest = later(latest, end);
            }
            return known ? latest : null;
        }
    }

    private static LocalDateTime earlier(LocalDateTime left, LocalDateTime right) {
        return left == null ? right : right == null || left.isBefore(right) ? left : right;
    }

    private static LocalDateTime later(LocalDateTime left, LocalDateTime right) {
        return left == null ? right : right == null || left.isAfter(right) ? left : right;
    }
}
