package com.richuang.os.nocode.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 管理者任务能效只读契约：标准计量、整项参考和自然历时分别呈现，不生成效率评分。 */
public final class TaskEfficiency {
    private TaskEfficiency() {}

    /** 日期包含首尾两天；页大小最多100，区间最多366天。排序字段由服务端白名单校验。 */
    public record Query(
            LocalDate from,
            LocalDate to,
            Long employeeId,
            String templateId,
            String rootTaskId,
            Integer pageNo,
            Integer pageSize,
            String search,
            String sortBy,
            Boolean descending) {}

    /** 标准工时归属首次有效计量日；当前待办、逾期不受日期限制。单位均为分钟。 */
    public record Metrics(
            BigDecimal standardMinutes,
            long recordCount,
            long employeeCount,
            long completedNodeCount,
            long activeNodeCount,
            long overdueNodeCount) {}

    public record Overview(
            BigDecimal standardMinutes,
            long recordCount,
            long employeeCount,
            long completedNodeCount,
            long activeNodeCount,
            long overdueNodeCount,
            List<Trend> trend,
            List<Distribution> employees) {}

    public record Trend(LocalDate date, BigDecimal standardMinutes, long recordCount) {}

    public record Distribution(
            long employeeId, String employeeName, BigDecimal standardMinutes, long recordCount) {}

    /** 节点完成按实际完成日计数；在办/逾期为当前执行叶节点，避免父子重复统计工作任务数。 */
    public record Employee(
            long employeeId,
            String employeeName,
            BigDecimal standardMinutes,
            long recordCount,
            long participatedTaskCount,
            long completedNodeCount,
            long activeNodeCount,
            long overdueNodeCount) {}

    /** 工时按期间和员工筛选计量；节点进度始终为整组当前快照。referenceMinutes只取总任务。 */
    public record Task(
            String rootTaskId,
            String title,
            String templateId,
            String templateName,
            Integer templateVersion,
            String status,
            Long assigneeId,
            String assigneeName,
            Integer referenceMinutes,
            BigDecimal standardMinutes,
            long recordCount,
            long employeeCount,
            long completedNodeCount,
            long totalNodeCount,
            long cancelledNodeCount,
            long overdueNodeCount,
            LocalDateTime expectedEnd,
            LocalDateTime actualStart,
            LocalDateTime actualEnd,
            BigDecimal elapsedMinutes) {}

    /** 一行是一份去重后的有效贡献；混合历史标准时 unitMinutes 为空，quantity 为真实数量而非工时倒除。 */
    public record Record(
            String taskId,
            String taskTitle,
            String rootTaskId,
            String rootTitle,
            String entryKey,
            String entryName,
            long employeeId,
            String employeeName,
            String recordId,
            TaskWorkEntries.WorkRuleMode ruleMode,
            BigDecimal unitMinutes,
            BigDecimal quantity,
            BigDecimal standardMinutes,
            LocalDateTime firstCountedAt,
            LocalDateTime lastHandledAt) {}

    public record EmployeeOption(long id, String name) {}

    public record TemplateOption(String id, String name) {}

    /** 候选仅来自当前管理范围，每类最多200项；可通过search搜索，模板候选不按自身已选值裁剪。 */
    public record Options(List<EmployeeOption> employees, List<TemplateOption> templates) {}
}
