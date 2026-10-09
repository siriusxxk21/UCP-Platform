package com.richuang.os.nocode.api.workflow;

import com.richuang.os.nocode.api.work.WorkDraftViews;

import java.time.LocalDateTime;
import java.util.List;

/** 本人流程草稿及已提交材料索引；不输出业务字段值或权限过滤前的总数。 */
public final class FlowTaskWorkViews {
    private FlowTaskWorkViews() {}

    /** 游标只控制扫描位置，查询时仍逐项校验任务资格及当前业务读取权限。 */
    public record Query(String state, WorkDraftViews.Cursor before, Integer limit) {
        public Query {
            if (limit == null) limit = 10;
        }
    }

    public record Item(
            String draftId,
            String taskId,
            String processInstanceId,
            String nodeId,
            String state,
            String formName,
            String objectName,
            String applicationId,
            int applicationVersion,
            LocalDateTime updatedAt,
            String submissionId,
            boolean writable,
            String blockedReason) {}

    /** 被撤权的候选不会返回；空页仍可能带有下一页游标。 */
    public record Page(List<Item> items, WorkDraftViews.Cursor before) {}
}
