package com.lingan.ucp.nocode.api;

import com.lingan.ucp.nocode.api.TaskCenter.AssignmentMode;
import com.lingan.ucp.nocode.api.TaskCenter.Priority;
import com.lingan.ucp.nocode.api.TaskCenter.Urgency;

import java.time.LocalDateTime;
import java.util.List;

/** 领取专用安全摘要：命中可领取项后展示整组结构，不承载业务资料或候选名单。 */
public final class TaskClaims {
    private TaskClaims() {}

    public record Query(
            String search, Urgency urgency, Priority priority, int pageNo, int pageSize) {}

    /** 领取目录归属摘要；RESTRICTED 不透露不可见总任务的人员配置。 */
    public enum Ownership {
        UNCLAIMED,
        MINE,
        ASSIGNED,
        UNAVAILABLE,
        RESTRICTED
    }

    /** includeOpen 为 true 时包含本人可领开放子项；本人已负责总任务时只补领子项，省略保留旧规则。 */
    public record Root(String rootId, Boolean includeOpen) {
        public Root(String rootId) {
            this(rootId, false);
        }
    }

    /** wholeClaimCount 含根；remainingClaimCount 仅供总负责人补领；人数和数量均受当前成员权限限制。 */
    public record Group(
            String rootId,
            String title,
            boolean rootVisible,
            boolean canClaimGroup,
            int claimableCount,
            int followRootCount,
            int wholeClaimCount,
            Ownership ownership,
            String ownerName,
            int claimableChildCount,
            int remainingClaimCount,
            String status,
            LocalDateTime expectedStart,
            LocalDateTime expectedEnd,
            Priority priority,
            int childCount,
            int completedChildCount,
            String anchorTaskId,
            TaskCenter.ScheduleSummary scheduleSummary) {
        /** 保留未提供排期摘要的旧投影构造。 */
        public Group(
                String rootId,
                String title,
                boolean rootVisible,
                boolean canClaimGroup,
                int claimableCount,
                int followRootCount,
                int wholeClaimCount,
                Ownership ownership,
                String ownerName,
                int claimableChildCount,
                int remainingClaimCount,
                String status,
                LocalDateTime expectedStart,
                LocalDateTime expectedEnd,
                Priority priority,
                int childCount,
                int completedChildCount,
                String anchorTaskId) {
            this(
                    rootId,
                    title,
                    rootVisible,
                    canClaimGroup,
                    claimableCount,
                    followRootCount,
                    wholeClaimCount,
                    ownership,
                    ownerName,
                    claimableChildCount,
                    remainingClaimCount,
                    status,
                    expectedStart,
                    expectedEnd,
                    priority,
                    childCount,
                    completedChildCount,
                    anchorTaskId,
                    null);
        }
    }

    /** 展开返回真实同组层级；可读、可领取与上下文显示独立，anchorTaskId 为有权打开的入口。 */
    public record Item(
            String id,
            String rootId,
            String parentId,
            String title,
            String status,
            Long assigneeId,
            AssignmentMode assignmentMode,
            int revision,
            Urgency urgency,
            Priority priority,
            LocalDateTime expectedStart,
            LocalDateTime expectedEnd,
            boolean canClaim,
            String assigneeName,
            boolean detailVisible,
            int childCount,
            int completedChildCount,
            String anchorTaskId,
            TaskCenter.ScheduleSummary scheduleSummary) {
        /** 保留未提供排期摘要的旧投影构造。 */
        public Item(
                String id,
                String rootId,
                String parentId,
                String title,
                String status,
                Long assigneeId,
                AssignmentMode assignmentMode,
                int revision,
                Urgency urgency,
                Priority priority,
                LocalDateTime expectedStart,
                LocalDateTime expectedEnd,
                boolean canClaim,
                String assigneeName,
                boolean detailVisible,
                int childCount,
                int completedChildCount,
                String anchorTaskId) {
            this(
                    id,
                    rootId,
                    parentId,
                    title,
                    status,
                    assigneeId,
                    assignmentMode,
                    revision,
                    urgency,
                    priority,
                    expectedStart,
                    expectedEnd,
                    canClaim,
                    assigneeName,
                    detailVisible,
                    childCount,
                    completedChildCount,
                    anchorTaskId,
                    null);
        }

        public Item(
                String id,
                String rootId,
                String parentId,
                String title,
                String status,
                Long assigneeId,
                AssignmentMode assignmentMode,
                int revision,
                Urgency urgency,
                Priority priority,
                LocalDateTime expectedStart,
                LocalDateTime expectedEnd,
                boolean canClaim) {
            this(
                    id,
                    rootId,
                    parentId,
                    title,
                    status,
                    assigneeId,
                    assignmentMode,
                    revision,
                    urgency,
                    priority,
                    expectedStart,
                    expectedEnd,
                    canClaim,
                    null,
                    false,
                    0,
                    0,
                    id);
        }
    }

    /** 只包含此次本人实际可领取节点；未纳入的分支保持不变，不开放其资料权。 */
    public record Preview(
            String rootId,
            String title,
            int instanceRevision,
            List<Item> items,
            boolean remainingOnly) {}

    public record ClaimGroup(
            String rootId, int expectedInstanceRevision, String requestKey, Boolean includeOpen) {
        public ClaimGroup(String rootId, int expectedInstanceRevision, String requestKey) {
            this(rootId, expectedInstanceRevision, requestKey, false);
        }
    }
}
