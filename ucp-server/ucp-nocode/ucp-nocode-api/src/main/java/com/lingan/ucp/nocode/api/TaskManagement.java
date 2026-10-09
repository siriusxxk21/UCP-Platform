package com.lingan.ucp.nocode.api;

import java.time.LocalDate;

/** 老板任务跟进的只读查询契约；个人清单不通过管理查询改变归属或内容。 */
public final class TaskManagement {
    private TaskManagement() {}

    /** 任务总览的关注范围，不改变任务真实状态。 */
    public enum Focus {
        ACTIVE,
        ALL,
        UNASSIGNED,
        OVERDUE,
        PENDING_ACCEPTANCE
    }

    /** RELATED 包含本人负责的所有状态节点；其他指标继续区分执行工作、协调工作及本人有效清单。 */
    public enum EmployeeMetric {
        RELATED,
        ALL,
        PENDING,
        RUNNING,
        OVERDUE,
        TODAY,
        WEEK,
        COORDINATION
    }

    /** 员工下钻可按根归组分页；groupByRoot 缺省为 false，兼容旧接口的匹配节点平铺。归组不扩大授权。 */
    public record Query(
            TaskCenter.Query query,
            Focus focus,
            Long employeeId,
            EmployeeMetric employeeMetric,
            Boolean groupByRoot) {
        /** 保留旧调用方的节点平铺语义。 */
        public Query(
                TaskCenter.Query query,
                Focus focus,
                Long employeeId,
                EmployeeMetric employeeMetric) {
            this(query, focus, employeeId, employeeMetric, false);
        }
    }

    /** 日期仅用于今日、本周清单；状态与逾期始终是当前情况，不伪造历史快照。 */
    public record Employees(String search, LocalDate date, int pageNo, int pageSize) {}

    /** 所有数量先在完整授权范围汇总，再按员工分页；不以本页任务推算总量。 */
    public record Employee(
            Long userId,
            String userName,
            long pendingCount,
            long runningCount,
            long overdueCount,
            long todayCount,
            long weekCount,
            long coordinationCount) {}
}
