package com.richuang.os.nocode.runtime.service.taskcenter;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.TaskCenter.EventType;
import com.richuang.os.nocode.api.TaskCenter.Period;
import com.richuang.os.nocode.api.TaskCenter.Plan;
import com.richuang.os.nocode.api.TaskPlanning;
import com.richuang.os.nocode.api.TaskPlanning.*;
import com.richuang.os.nocode.runtime.dal.dataobject.TaskHistoryDO;
import com.richuang.os.nocode.runtime.dal.dataobject.TaskPlanDO;
import com.richuang.os.nocode.runtime.dal.mapper.TaskCenterMapper;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskScheduling.Access;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskScheduling.Loaded;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** 今日、本周和下周独立引用；同负责人后代实时继承，沿用根锁、权限及改派归档，不复制记录。 */
@Component
public class TaskChecklists {
    @Resource private TaskCenterMapper store;
    @Resource private TaskScheduling scheduling;
    @Resource private ObjectMapper json;
    @Resource private PlatformTransactionManager transactionManager;
    private TransactionTemplate tx;

    @PostConstruct
    void initialize() {
        tx = new TransactionTemplate(transactionManager);
        json = json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public ChecklistContext context(ContextQuery query, long actor, Access access) {
        if (query == null) throw invalid("缺少清单上下文");
        List<String> ids = scheduling.ids(query.ids());
        Target target = scheduling.target(query.target());
        return tx.execute(
                status -> {
                    scheduling.lockRoots(ids);
                    LocalDate today = LocalDate.now(), week = week(today);
                    List<ChecklistItem> items = new ArrayList<>();
                    for (String id : ids) {
                        Loaded loaded = scheduling.load(id, target, actor, access, Mode.CHECKLIST);
                        List<Plan> days = current(loaded, Period.DAY, today, access);
                        List<Plan> weeks = current(loaded, Period.WEEK, week, access);
                        List<Plan> nextWeeks =
                                current(loaded, Period.WEEK, week.plusWeeks(1), access);
                        List<Plan> history =
                                store.planHistory(id).stream()
                                        .filter(
                                                p ->
                                                        access.manage(loaded.nodes(), actor)
                                                                || Objects.equals(
                                                                        p.getUserId(), actor))
                                        .filter(
                                                p ->
                                                        TaskScheduling.mode(p) == Mode.SCHEDULE
                                                                || Boolean.TRUE.equals(
                                                                        p.getDeleted())
                                                                || !matches(p, Period.DAY, today)
                                                                        && !matches(
                                                                                p,
                                                                                Period.WEEK,
                                                                                week)
                                                                        && !matches(
                                                                                p,
                                                                                Period.WEEK,
                                                                                week.plusWeeks(1)))
                                        .map(p -> scheduling.view(p, false, access))
                                        .toList();
                        List<String> warnings = new ArrayList<>();
                        String blocked = access.blocked(loaded.task(), loaded.nodes());
                        if (blocked != null && loaded.eligible())
                            warnings.add(blocked + "；加入清单不会开始任务");
                        items.add(
                                new ChecklistItem(
                                        id,
                                        loaded.task().getTitle(),
                                        loaded.task().getStatus(),
                                        loaded.task().getAssigneeId(),
                                        scheduling.version(loaded.task()),
                                        days,
                                        weeks,
                                        history,
                                        loaded.eligible(),
                                        loaded.readOnlyReason() != null
                                                ? loaded.readOnlyReason()
                                                : loaded.eligible()
                                                        ? null
                                                        : "任务待验收、已结束、尚未分配或没有清单操作权限",
                                        List.copyOf(warnings),
                                        nextWeeks));
                    }
                    return new ChecklistContext(today, week, week.plusDays(6), List.copyOf(items));
                });
    }

    /** 首次请求原子处理整批；重试先核对实时权限和完整指纹，再恢复原回执而非重复写入。 */
    public TaskPlanning.Result change(ChecklistChange command, long actor, Access access) {
        if (command == null || command.action() == null) throw invalid("请选择加入或移出清单");
        List<String> ids = scheduling.ids(command.ids());
        Target target = scheduling.target(command.target());
        if (command.period() != Period.DAY && command.period() != Period.WEEK)
            throw invalid("清单仅支持今日和本周、下周");
        if (command.expectedVersions() == null
                || !command.expectedVersions().keySet().equals(new HashSet<>(ids)))
            throw invalid("请先加载全部任务的当前清单修订");
        Set<String> selected =
                new LinkedHashSet<>(command.planIds() == null ? List.of() : command.planIds());
        if (selected.stream().anyMatch(id -> id == null || id.isBlank())
                || selected.size() != (command.planIds() == null ? 0 : command.planIds().size()))
            throw invalid("清单项标识无效或重复");
        if (command.action() == ChecklistAction.REMOVE && selected.isEmpty())
            throw invalid("请选择要移出的真实清单项");
        if (command.action() == ChecklistAction.ADD && !selected.isEmpty())
            throw invalid("加入清单不能指定移出项");
        String key = TaskGraph.text(command.requestKey(), "请求标识", 120, true);
        String hash = fingerprint(command);
        return tx.execute(
                status -> {
                    store.requestLock("task-checklist:" + actor + ":" + key);
                    scheduling.lockRoots(ids);
                    List<Loaded> loaded =
                            ids.stream()
                                    .map(
                                            id ->
                                                    scheduling.load(
                                                            id,
                                                            target,
                                                            actor,
                                                            access,
                                                            Mode.CHECKLIST))
                                    .toList();
                    if (loaded.stream().anyMatch(item -> !item.authorized()))
                        throw invalid("仅当前任务负责人本人可以维护清单");
                    loaded.forEach(scheduling::requireActive);
                    TaskHistoryDO receipt = store.requested(Long.toString(actor), key);
                    if (receipt != null) {
                        if (!hash.equals(receipt.getRequestHash()))
                            throw invalid("请求标识已用于其他内容，请勿重复使用");
                        return replay(ids, actor, hash);
                    }
                    // 回执重放允许跨午夜；新的写入必须重新确认服务器当前日期，不能偷偷写到昨日。
                    LocalDate today = LocalDate.now();
                    LocalDate anchor = command.date();
                    boolean validDate =
                            command.period() == Period.DAY
                                    ? today.equals(anchor)
                                    : week(today).equals(anchor)
                                            || week(today).plusWeeks(1).equals(anchor);
                    if (!validDate) throw invalid("清单日期已变化，请刷新后加入或移出当前清单");
                    // 同批先安排祖先，再刷新后代有效项；不能因输入顺序产生多份显式计划。
                    if (command.action() == ChecklistAction.ADD)
                        loaded =
                                loaded.stream()
                                        .sorted(Comparator.comparingInt(this::depth))
                                        .toList();
                    List<String> changed = new ArrayList<>(), unchanged = new ArrayList<>();
                    Set<String> matched = new HashSet<>();
                    for (Loaded initial : loaded) {
                        Loaded item =
                                command.action() == ChecklistAction.ADD
                                        ? scheduling.load(
                                                initial.task().getId(),
                                                target,
                                                actor,
                                                access,
                                                Mode.CHECKLIST)
                                        : initial;
                        String id = item.task().getId();
                        if (!Objects.equals(
                                command.expectedVersions().get(id),
                                scheduling.version(item.task())))
                            throw new ServiceException(CONFLICT, "任务清单已变化，请刷新后重试");
                        if (!item.eligible()) throw invalid("待验收、已结束或尚未分配的任务不能变更清单");
                        boolean modified;
                        if (command.action() == ChecklistAction.ADD) {
                            modified = add(item, command.period(), anchor, actor);
                            if (command.period() == Period.DAY
                                    && item.current().stream()
                                            .noneMatch(p -> matches(p, Period.WEEK, week(today))))
                                modified |= add(item, Period.WEEK, week(today), actor);
                        } else {
                            List<TaskPlanDO> removing =
                                    item.current().stream()
                                            .filter(
                                                    p ->
                                                            selected.contains(p.getId())
                                                                    && matches(
                                                                            p,
                                                                            command.period(),
                                                                            anchor))
                                            .toList();
                            if (removing.isEmpty()) throw invalid("任务没有所选当前清单项，不能移出");
                            for (TaskPlanDO plan : removing) {
                                if (plan.isInherited()) throw invalid("继承的清单不能在子任务移出，请调整来源任务的计划");
                                if (!canRemove(item, plan)) throw invalid("仅当前任务负责人本人可以移出清单");
                                scheduling.archive(plan, "REMOVED_FROM_CHECKLIST", actor);
                                matched.add(plan.getId());
                            }
                            modified = true;
                        }
                        if (modified) {
                            scheduling.advance(item.task(), actor);
                            changed.add(id);
                        } else unchanged.add(id);
                        saveReceipt(
                                item,
                                command,
                                hash,
                                id.equals(ids.getFirst()) ? key : null,
                                modified,
                                actor);
                    }
                    if (command.action() == ChecklistAction.REMOVE && !matched.equals(selected))
                        throw invalid("所选清单项已变化或不属于当前清单，请刷新后重试");
                    // 内部按层级执行，回执仍沿请求顺序，保持首次与幂等重放的响应一致。
                    return new TaskPlanning.Result(
                            ids.stream().filter(changed::contains).toList(),
                            ids.stream().filter(unchanged::contains).toList());
                });
    }

    private List<Plan> current(Loaded loaded, Period period, LocalDate date, Access access) {
        return loaded.current().stream()
                .filter(p -> matches(p, period, date))
                .map(p -> scheduling.view(p, canRemove(loaded, p), access))
                .toList();
    }

    private boolean canRemove(Loaded loaded, TaskPlanDO plan) {
        // 历史 MANAGER 来源只保留审计意义，当前负责人可自行移出自己的清单。
        return loaded.eligible()
                && !plan.isInherited()
                && Objects.equals(plan.getTaskId(), loaded.task().getId())
                && Objects.equals(plan.getUserId(), loaded.owner());
    }

    private int depth(Loaded loaded) {
        Map<String, String> parents = new HashMap<>();
        loaded.nodes().forEach(node -> parents.put(node.getId(), node.getParentId()));
        Set<String> visited = new HashSet<>();
        String id = loaded.task().getId();
        int depth = 0;
        while (id != null && visited.add(id)) {
            id = parents.get(id);
            depth++;
        }
        return depth;
    }

    private boolean add(Loaded loaded, Period period, LocalDate date, long actor) {
        if (loaded.current().stream().anyMatch(p -> matches(p, period, date))) return false;
        TaskPlanDO plan = new TaskPlanDO();
        plan.setId(UUID.randomUUID().toString());
        plan.setTaskId(loaded.task().getId());
        plan.setUserId(loaded.owner());
        plan.setPlanMode(Mode.CHECKLIST.name());
        plan.setPeriod(period.name());
        plan.setPlanDate(date);
        plan.setEndDate(period == Period.DAY ? date : date.plusDays(6));
        plan.setSource("SELF");
        plan.setArrangedById(actor);
        plan.setArrangedAt(LocalDateTime.now());
        store.savePlan(plan, Long.toString(actor));
        return true;
    }

    private static LocalDate week(LocalDate date) {
        return TaskScheduling.range(Period.WEEK, date, null).date();
    }

    private static boolean matches(TaskPlanDO plan, Period period, LocalDate date) {
        return period.name().equals(plan.getPeriod()) && date.equals(plan.getPlanDate());
    }

    static boolean current(TaskPlanDO plan, LocalDate today) {
        return matches(plan, Period.DAY, today)
                || matches(plan, Period.WEEK, week(today))
                || matches(plan, Period.WEEK, week(today).plusWeeks(1));
    }

    /** 回执每个任务仅保存自身结果，避免批量操作将其他私有任务 ID 暴露在事件材料中。 */
    public record Receipt(
            Mode mode, ChecklistAction action, Period period, LocalDate date, boolean changed) {}

    private void saveReceipt(
            Loaded item,
            ChecklistChange command,
            String hash,
            String key,
            boolean changed,
            long actor) {
        TaskHistoryDO event = new TaskHistoryDO();
        event.setId(UUID.randomUUID().toString());
        event.setTaskId(item.task().getId());
        event.setRootId(item.task().getRootId());
        event.setEventType(EventType.PLANNED.name());
        event.setRequestKey(key);
        event.setRequestHash(hash);
        String label =
                command.period() == Period.DAY
                        ? "今日清单"
                        : command.date().equals(week(LocalDate.now()).plusWeeks(1))
                                ? "下周清单"
                                : "本周清单";
        event.setNote(
                changed
                        ? (command.action() == ChecklistAction.ADD ? "加入" : "移出") + label
                        : "已在" + label + "，保留现有清单");
        try {
            event.setMaterialJson(
                    json.writeValueAsString(
                            new Receipt(
                                    Mode.CHECKLIST,
                                    command.action(),
                                    command.period(),
                                    command.date(),
                                    changed)));
        } catch (Exception ex) {
            throw invalid("清单操作记录无法保存");
        }
        store.appendEvent(event, Long.toString(actor));
    }

    private TaskPlanning.Result replay(List<String> ids, long actor, String hash) {
        List<String> changed = new ArrayList<>(), unchanged = new ArrayList<>();
        for (String id : ids) {
            TaskHistoryDO event = store.checklistReceipt(id, Long.toString(actor), hash);
            try {
                Receipt receipt = json.readValue(event.getMaterialJson(), Receipt.class);
                if (receipt.mode() != Mode.CHECKLIST) throw new IllegalStateException();
                (receipt.changed() ? changed : unchanged).add(id);
            } catch (Exception ex) {
                throw invalid("原清单回执无法读取，请刷新后核对当前清单");
            }
        }
        return new TaskPlanning.Result(List.copyOf(changed), List.copyOf(unchanged));
    }

    private String fingerprint(ChecklistChange command) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            ("CHECKLIST:" + json.writeValueAsString(command))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw invalid("清单请求无法编码");
        }
    }
}
