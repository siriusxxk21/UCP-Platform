package com.lingan.ucp.nocode.runtime.service.taskcenter;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.api.TaskPlanning;
import com.lingan.ucp.nocode.api.TaskPlanning.Target;
import com.lingan.ucp.nocode.runtime.dal.dataobject.*;
import com.lingan.ucp.nocode.runtime.dal.mapper.TaskCenterMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** 计划仅由当前负责人维护；管理查看与写入权限分离，沿用根锁与历史审计。 */
@Component
public class TaskScheduling {
    public interface Access {
        boolean visible(TaskInstanceDO task, List<TaskInstanceDO> nodes, long actor);

        boolean arrange(TaskInstanceDO task, List<TaskInstanceDO> nodes, long actor);

        boolean readPlans(TaskInstanceDO task, List<TaskInstanceDO> nodes, long actor);

        boolean manage(List<TaskInstanceDO> nodes, long actor);

        String name(Long actor);

        String blocked(TaskInstanceDO task, List<TaskInstanceDO> nodes);
    }

    @Resource private TaskCenterMapper store;
    @Resource private TaskWorkflowProtection workflowProtection;
    @Resource private ObjectMapper json;
    @Resource private PlatformTransactionManager transactionManager;
    private TransactionTemplate tx;

    @PostConstruct
    void initialize() {
        tx = new TransactionTemplate(transactionManager);
    }

    public record Range(Period period, LocalDate date, LocalDate endDate) {}

    /** 周/月按完整自然期间保存；DAY 才允许显式日期区间，结束日期包含当天。 */
    public static Range range(Period period, LocalDate date, LocalDate endDate) {
        if (period == null || date == null) throw invalid("请选择计划期间和日期");
        LocalDate from =
                switch (period) {
                    case DAY -> date;
                    case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                    case MONTH -> date.withDayOfMonth(1);
                };
        LocalDate end =
                switch (period) {
                    case DAY -> endDate == null ? date : endDate;
                    case WEEK -> from.plusDays(6);
                    case MONTH -> from.plusMonths(1).minusDays(1);
                };
        if (end.isBefore(from)) throw invalid("计划结束日期不能早于开始日期");
        if (period != Period.DAY && endDate != null && !end.equals(endDate))
            throw invalid("周/月计划使用完整期间，请细化为具体日期后设置日期范围");
        return new Range(period, from, end);
    }

    public TaskPlanning.Context context(
            TaskPlanning.ContextQuery query, long actor, Access access) {
        if (query == null) throw invalid("缺少计划上下文");
        List<String> ids = ids(query.ids());
        Target target = target(query.target());
        return tx.execute(
                status -> {
                    lockRoots(ids);
                    List<TaskPlanning.Item> items = new ArrayList<>();
                    for (String id : ids) {
                        Loaded loaded = load(id, target, actor, access);
                        TaskInstanceDO task = loaded.task();
                        List<TaskPlanDO> current = loaded.current();
                        boolean protectedDate =
                                current.stream()
                                        .anyMatch(p -> manager(p) && "DAY".equals(p.getPeriod()));
                        boolean eligible = loaded.eligible();
                        String reason =
                                loaded.readOnlyReason() != null
                                        ? loaded.readOnlyReason()
                                        : !eligible
                                                ? (ended(task) ? "任务已结束，计划仅供查看" : "没有为当前负责人安排计划的权限")
                                                : protectedDate
                                                        ? "历史主管排期仅供查看，当前计划请使用今日或本周清单"
                                                        : null;
                        List<String> warnings = new ArrayList<>();
                        String blocked = access.blocked(task, loaded.nodes());
                        if (blocked != null && !ended(task)) warnings.add(blocked + "；安排计划不会开始任务");
                        if (task.getExpectedEnd() != null
                                && task.getExpectedEnd().isBefore(LocalDateTime.now())
                                && !ended(task)) warnings.add("任务预计截止时间已过，安排计划不会修改预计截止时间");
                        if (current.stream().anyMatch(TaskScheduling::manager))
                            warnings.add("历史主管排期仅供查看，不限制今日或本周清单");
                        List<TaskPlanDO> constraints =
                                current.stream().filter(TaskScheduling::manager).toList();
                        if (!constraints.isEmpty()) {
                            LocalDate maxStart =
                                    constraints.stream()
                                            .map(TaskPlanDO::getPlanDate)
                                            .max(LocalDate::compareTo)
                                            .orElseThrow();
                            LocalDate minEnd =
                                    constraints.stream()
                                            .map(TaskPlanDO::getEndDate)
                                            .min(LocalDate::compareTo)
                                            .orElseThrow();
                            if (maxStart.isAfter(minEnd)) warnings.add("现有主管安排日期范围冲突，请联系安排人调整");
                        }
                        List<Plan> plans =
                                current.stream()
                                        .map(p -> view(p, eligible && !manager(p), access))
                                        .toList();
                        List<Plan> history =
                                store.planHistory(id).stream()
                                        .filter(p -> mode(p) == TaskPlanning.Mode.SCHEDULE)
                                        .filter(p -> Boolean.TRUE.equals(p.getDeleted()))
                                        .filter(
                                                p ->
                                                        access.manage(loaded.nodes(), actor)
                                                                || Objects.equals(
                                                                        p.getUserId(), actor))
                                        .map(p -> view(p, false, access))
                                        .toList();
                        items.add(
                                new TaskPlanning.Item(
                                        id,
                                        task.getTitle(),
                                        task.getStatus(),
                                        task.getAssigneeId(),
                                        version(task),
                                        plans,
                                        plans.stream()
                                                .filter(p -> "MANAGER".equals(p.source()))
                                                .toList(),
                                        history,
                                        eligible && !protectedDate,
                                        plans.stream()
                                                .anyMatch(p -> Boolean.TRUE.equals(p.canCancel())),
                                        !eligible
                                                || protectedDate
                                                        && plans.stream()
                                                                .noneMatch(
                                                                        p ->
                                                                                Boolean.TRUE.equals(
                                                                                        p
                                                                                                .canCancel())),
                                        reason,
                                        List.copyOf(warnings)));
                    }
                    return new TaskPlanning.Context(List.copyOf(items));
                });
    }

    public TaskPlanning.Result change(TaskPlanning.Change command, long actor, Access access) {
        if (command == null || command.action() == null) throw invalid("请选择安排或取消计划");
        List<String> ids = ids(command.ids());
        Target target = target(command.target());
        if (command.expectedVersions() == null
                || !command.expectedVersions().keySet().equals(new HashSet<>(ids)))
            throw invalid("请先加载全部任务的当前计划修订");
        Range range =
                command.action() == TaskPlanning.Action.ARRANGE
                        ? range(command.period(), command.date(), command.endDate())
                        : null;
        Set<String> selected =
                new LinkedHashSet<>(command.planIds() == null ? List.of() : command.planIds());
        if (selected.contains(null)
                || selected.contains("")
                || selected.size() != (command.planIds() == null ? 0 : command.planIds().size()))
            throw invalid("计划标识无效或重复");
        if (command.action() == TaskPlanning.Action.CANCEL && selected.isEmpty())
            throw invalid("请选择要取消的真实计划");
        if (command.action() == TaskPlanning.Action.ARRANGE && !selected.isEmpty())
            throw invalid("安排计划不能指定取消项");
        return tx.execute(
                status -> {
                    lockRoots(ids);
                    List<String> changed = new ArrayList<>(), unchanged = new ArrayList<>();
                    Set<String> matched = new HashSet<>();
                    for (String id : ids) {
                        Loaded loaded = load(id, target, actor, access);
                        if (!loaded.authorized()) throw invalid("仅当前任务负责人本人可以维护计划");
                        requireActive(loaded);
                        if (!Objects.equals(
                                command.expectedVersions().get(id), version(loaded.task())))
                            throw conflict();
                        if (!loaded.eligible()) throw invalid("任务已结束、尚未分配或没有计划安排权限");
                        boolean modified;
                        Object auditMaterial = range;
                        if (command.action() == TaskPlanning.Action.CANCEL) {
                            List<TaskPlanDO> cancelling =
                                    loaded.current().stream()
                                            .filter(p -> selected.contains(p.getId()))
                                            .toList();
                            if (cancelling.isEmpty()) throw invalid("任务没有所选有效计划，不能取消");
                            for (TaskPlanDO plan : cancelling) {
                                if (manager(plan)) throw invalid("历史主管排期仅供查看");
                                archive(plan, "CANCELLED", actor);
                                matched.add(plan.getId());
                            }
                            auditMaterial = cancelling.stream().map(TaskPlanDO::getId).toList();
                            modified = true;
                        } else modified = arrange(loaded, range, actor, false);
                        if (modified) {
                            advance(loaded.task(), actor);
                            audit(loaded.task(), command.action().name(), actor, auditMaterial);
                            changed.add(id);
                        } else unchanged.add(id);
                    }
                    if (command.action() == TaskPlanning.Action.CANCEL && !matched.equals(selected))
                        throw invalid("所选计划已变化或不属于当前任务，请刷新后重试");
                    return new TaskPlanning.Result(List.copyOf(changed), List.copyOf(unchanged));
                });
    }

    /** 旧端点保留独立添加和精确期间定位，但不能绕过主管保护或假报不存在计划取消成功。 */
    public void legacy(SavePlan command, long actor, Access access) {
        if (command == null) throw invalid("缺少计划安排");
        List<String> ids = ids(command.ids());
        Target target;
        try {
            target = command.target() == null ? Target.SELF : Target.valueOf(command.target());
        } catch (IllegalArgumentException ex) {
            throw invalid("计划安排目标无效");
        }
        Range range =
                range(
                        command.period(),
                        command.date() == null ? LocalDate.now() : command.date(),
                        null);
        Target resolved = target;
        tx.executeWithoutResult(
                status -> {
                    lockRoots(ids);
                    for (String id : ids) {
                        Loaded loaded = load(id, resolved, actor, access);
                        if (!loaded.authorized()) throw invalid("仅当前任务负责人本人可以维护计划");
                        requireActive(loaded);
                        if (!loaded.eligible()) throw invalid("待验收或已结束任务的计划仅供查看");
                        boolean modified;
                        if (command.include()) modified = arrange(loaded, range, actor, true);
                        else {
                            List<TaskPlanDO> cancelling =
                                    loaded.current().stream().filter(p -> same(p, range)).toList();
                            if (cancelling.isEmpty()) throw invalid("任务没有该期间的有效计划，不能取消");
                            for (TaskPlanDO plan : cancelling) {
                                if (manager(plan)) throw invalid("历史主管排期仅供查看");
                                archive(plan, "CANCELLED", actor);
                            }
                            modified = true;
                        }
                        if (modified) {
                            advance(loaded.task(), actor);
                            audit(
                                    loaded.task(),
                                    command.include() ? "ARRANGE" : "CANCEL",
                                    actor,
                                    range);
                        }
                    }
                });
    }

    private boolean arrange(Loaded loaded, Range range, long actor, boolean legacy) {
        String source = "SELF";
        List<TaskPlanDO> existing =
                loaded.current().stream().filter(p -> source.equals(p.getSource())).toList();
        // 重复旧调用不覆盖首次安排来源；新接口则以来源分层，并明确保留主管约束。
        if (legacy && loaded.current().stream().anyMatch(p -> same(p, range))) return false;
        for (TaskPlanDO constraint : loaded.current()) {
            if (!manager(constraint)) continue;
            if (rank(range.period()) >= rank(Period.valueOf(constraint.getPeriod())))
                throw invalid("只能细化主管周/月安排，不能自行更改主管计划");
            if (!within(
                    range.date(),
                    range.endDate(),
                    constraint.getPlanDate(),
                    constraint.getEndDate())) throw invalid("细化日期必须在主管安排范围内");
        }
        if (!legacy && existing.size() == 1 && same(existing.getFirst(), range)) return false;
        if (!legacy) for (TaskPlanDO plan : existing) archive(plan, "RESCHEDULED", actor);
        TaskPlanDO plan = new TaskPlanDO();
        plan.setId(UUID.randomUUID().toString());
        plan.setTaskId(loaded.task().getId());
        plan.setUserId(loaded.owner());
        plan.setPeriod(range.period().name());
        plan.setPlanMode(TaskPlanning.Mode.SCHEDULE.name());
        plan.setPlanDate(range.date());
        plan.setEndDate(range.endDate());
        plan.setArrangedById(actor);
        plan.setSource(source);
        plan.setArrangedAt(LocalDateTime.now());
        store.savePlan(plan, Long.toString(actor));
        return true;
    }

    record Loaded(
            TaskInstanceDO task,
            List<TaskInstanceDO> nodes,
            Long owner,
            List<TaskPlanDO> current,
            boolean authorized,
            boolean eligible,
            String readOnlyReason) {}

    void requireActive(Loaded loaded) {
        workflowProtection.requireActive(loaded.task().getRootId());
    }

    private Loaded load(String id, Target target, long actor, Access access) {
        return load(id, target, actor, access, TaskPlanning.Mode.SCHEDULE);
    }

    Loaded load(String id, Target target, long actor, Access access, TaskPlanning.Mode mode) {
        TaskInstanceDO task = store.planningTask(id);
        if (task == null) throw invalid("任务不存在或没有计划访问权限");
        List<TaskInstanceDO> nodes = store.instance(task.getRootId());
        if (!access.visible(task, nodes, actor)) throw invalid("任务不存在或没有计划查看权限");
        Long owner = target == Target.SELF ? Long.valueOf(actor) : task.getAssigneeId();
        boolean authorized =
                owner != null
                        && owner.equals(task.getAssigneeId())
                        && owner.equals(actor)
                        && access.arrange(task, nodes, actor);
        // 管理人员可以读取当前负责人的计划，但不能借 ASSIGNEE 目标代写。
        boolean readable =
                owner != null
                        && owner.equals(task.getAssigneeId())
                        && access.readPlans(task, nodes, actor);
        List<TaskPlanDO> current =
                !readable
                        ? List.of()
                        : (mode == TaskPlanning.Mode.CHECKLIST
                                        ? effectivePlans(task, nodes, owner, actor, access)
                                        : store.plans(owner, List.of(id)))
                                .stream().filter(p -> mode(p) == mode).toList();
        String readOnlyReason =
                workflowProtection.readOnlyReasons(List.of(task.getRootId())).get(task.getRootId());
        return new Loaded(
                task,
                nodes,
                owner,
                current,
                authorized,
                authorized
                        && readOnlyReason == null
                        && !ended(task)
                        && nodes.stream()
                                .noneMatch(
                                        n -> State.PENDING_ACCEPTANCE.name().equals(n.getStatus())),
                readOnlyReason);
    }

    /** 继承仅供读取；来源必须另过节点可见性，不能借计划泄露隐藏上级。 */
    List<TaskPlanDO> effectivePlans(
            TaskInstanceDO task,
            List<TaskInstanceDO> nodes,
            long owner,
            long actor,
            Access access) {
        List<TaskPlanDO> plans = store.effectivePlans(owner, List.of(task.getId()));
        Map<String, TaskInstanceDO> byId = new HashMap<>();
        for (TaskInstanceDO node : nodes) byId.put(node.getId(), node);
        for (TaskPlanDO plan : plans) {
            if (!plan.isInherited()) continue;
            TaskInstanceDO ancestor = byId.get(plan.getInheritedFromTaskId());
            if (ancestor != null && access.visible(ancestor, nodes, actor)) {
                plan.setInheritedFromTitle(ancestor.getTitle());
            } else {
                plan.setInheritedFromTaskId(null);
                plan.setInheritedFromTitle(null);
            }
        }
        return plans;
    }

    public Plan view(TaskPlanDO plan, Boolean canCancel, Access access) {
        return new Plan(
                Period.valueOf(plan.getPeriod()),
                plan.getPlanDate(),
                plan.getUserId(),
                plan.getArrangedById(),
                access.name(plan.getArrangedById()),
                plan.getSource(),
                plan.getArrangedAt(),
                plan.isInherited() ? null : plan.getId(),
                plan.getEndDate(),
                !Boolean.TRUE.equals(plan.getDeleted()),
                plan.getHistoryReason(),
                plan.isInherited() ? Boolean.FALSE : canCancel,
                access.name(plan.getUserId()),
                mode(plan),
                plan.isInherited() ? Boolean.TRUE : null,
                plan.getInheritedFromTaskId(),
                plan.getInheritedFromTitle());
    }

    static TaskPlanning.Mode mode(TaskPlanDO plan) {
        return plan.getPlanMode() == null
                ? TaskPlanning.Mode.SCHEDULE
                : TaskPlanning.Mode.valueOf(plan.getPlanMode());
    }

    void lockRoots(List<String> ids) {
        List<String> roots =
                ids.stream()
                        .map(this::require)
                        .map(TaskInstanceDO::getRootId)
                        .distinct()
                        .sorted()
                        .toList();
        for (String root : roots) if (store.get(root, true) == null) throw invalid("任务不存在或已移除");
    }

    private TaskInstanceDO require(String id) {
        TaskInstanceDO task = store.get(id, false);
        if (task == null) throw invalid("任务不存在或没有计划访问权限");
        return task;
    }

    List<String> ids(List<String> ids) {
        if (ids == null
                || ids.isEmpty()
                || ids.size() > 100
                || ids.stream().anyMatch(id -> id == null || id.isBlank())
                || new HashSet<>(ids).size() != ids.size()) throw invalid("请选择 1 至 100 个不重复的任务");
        return List.copyOf(ids);
    }

    Target target(Target target) {
        return target == null ? Target.SELF : target;
    }

    static boolean manager(TaskPlanDO plan) {
        return "MANAGER".equals(plan.getSource());
    }

    private boolean ended(TaskInstanceDO task) {
        return State.COMPLETED.name().equals(task.getStatus())
                || State.CANCELLED.name().equals(task.getStatus());
    }

    private static int rank(Period period) {
        return switch (period) {
            case DAY -> 0;
            case WEEK -> 1;
            case MONTH -> 2;
        };
    }

    private static boolean within(
            LocalDate from, LocalDate to, LocalDate boundFrom, LocalDate boundTo) {
        return !from.isBefore(boundFrom) && !to.isAfter(boundTo);
    }

    private static boolean same(TaskPlanDO plan, Range range) {
        return plan.getPeriod().equals(range.period().name())
                && plan.getPlanDate().equals(range.date())
                && plan.getEndDate().equals(range.endDate());
    }

    int version(TaskInstanceDO task) {
        return task.getScheduleVersion() == null ? 0 : task.getScheduleVersion();
    }

    void advance(TaskInstanceDO task, long actor) {
        if (store.advanceSchedule(task.getId(), version(task), Long.toString(actor)) != 1)
            throw conflict();
    }

    void archive(TaskPlanDO plan, String reason, long actor) {
        if (plan.isInherited()) throw invalid("继承的清单不能在子任务移出，请调整来源任务的计划");
        if (store.archivePlan(plan.getId(), reason, Long.toString(actor)) != 1) throw conflict();
    }

    private ServiceException conflict() {
        return new ServiceException(CONFLICT, "任务计划已变化，请重新加载当前安排");
    }

    private void audit(TaskInstanceDO task, String action, long actor, Object material) {
        TaskHistoryDO event = new TaskHistoryDO();
        event.setId(UUID.randomUUID().toString());
        event.setTaskId(task.getId());
        event.setRootId(task.getRootId());
        event.setEventType(EventType.PLANNED.name());
        event.setNote("CANCEL".equals(action) ? "取消所选计划" : "安排或调整执行计划");
        try {
            event.setMaterialJson(json.writeValueAsString(material));
        } catch (Exception ex) {
            throw invalid("计划操作记录无法保存");
        }
        store.appendEvent(event, Long.toString(actor));
    }
}
