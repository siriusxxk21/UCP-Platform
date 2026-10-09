package com.richuang.os.nocode.api;

import com.richuang.os.nocode.api.TaskCenter.Period;
import com.richuang.os.nocode.api.TaskCenter.Plan;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 个人/团队统一计划；安排仅引用任务，不改变任务的预计和实际执行日期。 */
public final class TaskPlanning {
    private TaskPlanning() {}

    public enum Target {
        SELF,
        ASSIGNEE
    }

    public enum Action {
        ARRANGE,
        CANCEL
    }

    public enum Scope {
        PERSONAL,
        TEAM
    }

    public enum Filter {
        UNPLANNED,
        CARRYOVER,
        COARSE,
        PLANNED
    }

    /** 缺省仍是旧区间排期；清单按日/周独立保存，不通过区间投影互相推导。 */
    public enum Mode {
        SCHEDULE,
        CHECKLIST
    }

    public enum ChecklistAction {
        ADD,
        REMOVE
    }

    public record ChecklistContext(
            LocalDate today,
            LocalDate weekStart,
            LocalDate weekEnd,
            List<ChecklistItem> items,
            LocalDate nextWeekStart,
            LocalDate nextWeekEnd) {
        public ChecklistContext(
                LocalDate today,
                LocalDate weekStart,
                LocalDate weekEnd,
                List<ChecklistItem> items) {
            this(today, weekStart, weekEnd, items, weekStart.plusWeeks(1), weekEnd.plusWeeks(1));
        }
    }

    public record ChecklistItem(
            String taskId,
            String title,
            String status,
            Long assigneeId,
            int version,
            List<Plan> todayPlans,
            List<Plan> weekPlans,
            List<Plan> history,
            boolean canAdd,
            String reason,
            List<String> warnings,
            List<Plan> nextWeekPlans) {
        public ChecklistItem(
                String taskId,
                String title,
                String status,
                Long assigneeId,
                int version,
                List<Plan> todayPlans,
                List<Plan> weekPlans,
                List<Plan> history,
                boolean canAdd,
                String reason,
                List<String> warnings) {
            this(
                    taskId,
                    title,
                    status,
                    assigneeId,
                    version,
                    todayPlans,
                    weekPlans,
                    history,
                    canAdd,
                    reason,
                    warnings,
                    List.of());
        }
    }

    /** 日期使用上下文返回的当前锚点；同请求标识及完整内容可恢复原批次回执。 */
    public record ChecklistChange(
            List<String> ids,
            Target target,
            ChecklistAction action,
            Period period,
            LocalDate date,
            List<String> planIds,
            Map<String, Integer> expectedVersions,
            String requestKey) {}

    public record ContextQuery(List<String> ids, Target target) {}

    public record Context(List<Item> items) {}

    public record Item(
            String taskId,
            String title,
            String status,
            Long assigneeId,
            int version,
            List<Plan> plans,
            List<Plan> constraints,
            List<Plan> history,
            boolean canArrange,
            boolean canCancel,
            boolean readOnly,
            String reason,
            List<String> warnings) {}

    /** 取消必须明确计划身份；修订按任务检查，批量请求全部成功或全部回滚。 */
    public record Change(
            List<String> ids,
            Target target,
            Action action,
            Period period,
            LocalDate date,
            LocalDate endDate,
            List<String> planIds,
            Map<String, Integer> expectedVersions) {}

    public record Result(List<String> changed, List<String> unchanged) {}
}
