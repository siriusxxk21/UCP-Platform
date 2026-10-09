package com.lingan.ucp.nocode.api;

import java.math.BigDecimal;
import java.util.List;

/** 工时预算与运行中调价；与日历排期、任务办理权限相互独立。 */
public final class TaskWorkTimes {
    private TaskWorkTimes() {}

    public enum TotalMode {
        AUTO,
        MANUAL
    }

    /** 统一继承的表单只列总任务一行；历史独立表单按其配置节点分别列出。 */
    public record Entry(String taskId, String taskTitle, TaskWorkEntries.Config config) {}

    public record Context(
            String rootId,
            String rootTitle,
            int expectedRevision,
            TotalMode workTotalMode,
            Integer effectiveWorkMinutes,
            List<Entry> entries,
            boolean canAdjust,
            String disabledReason) {}

    /** minutes 为调整后的每单位工时；0 表示后续停计，历史工时保留；预计量可空，不影响办理。 */
    public record EntryChange(
            String taskId, String entryKey, int minutes, BigDecimal plannedQuantity) {}

    public record Change(
            String rootId,
            int expectedRevision,
            String requestKey,
            String reason,
            TotalMode workTotalMode,
            Integer effectiveWorkMinutes,
            List<EntryChange> entries) {}
}
