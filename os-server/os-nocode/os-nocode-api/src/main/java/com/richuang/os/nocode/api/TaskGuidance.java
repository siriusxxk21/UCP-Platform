package com.richuang.os.nocode.api;

import java.util.List;

/** 只读任务收尾检查与取消影响；操作提交仍重新鉴权和校验。 */
public final class TaskGuidance {
    private TaskGuidance() {}

    public enum CheckCode {
        STATE,
        ASSIGNEE,
        CHILDREN,
        CHILDREN_CANCELLED,
        BUSINESS,
        FEEDBACK
    }

    public record Check(
            CheckCode code, String label, boolean passed, String reason, String entryKey) {}

    public record CancellationImpact(String taskId, String title, boolean direct, String reason) {}

    public record Readiness(
            String taskId,
            int revision,
            boolean canComplete,
            List<Check> checks,
            boolean canCancel,
            String cancelBlockedReason,
            List<CancellationImpact> cancellationImpacts) {}
}
