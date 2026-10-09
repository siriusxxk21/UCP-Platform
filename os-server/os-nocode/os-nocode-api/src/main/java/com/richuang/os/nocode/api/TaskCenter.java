package com.richuang.os.nocode.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.richuang.os.nocode.api.work.PublishedResourceRef;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 常规任务中心契约；业务值仍通过公共记录契约存取，不建立第二套字段定义。 */
public final class TaskCenter {
    private TaskCenter() {}

    public enum State {
        PENDING,
        RUNNING,
        PAUSED,
        PENDING_ACCEPTANCE,
        COMPLETED,
        CANCELLED
    }

    public enum TimeMode {
        AUTO,
        UNSCHEDULED,
        PLAN_START,
        FIXED,
        T0,
        PREDECESSOR
    }

    /** 新任务的人员安排；旧请求省略时沿用原负责人默认规则。 */
    public enum AssignmentMode {
        UNASSIGNED,
        ASSIGNED,
        OPEN,
        FOLLOW_ROOT
    }

    public enum Period {
        DAY,
        WEEK,
        MONTH
    }

    public enum Action {
        START,
        PAUSE,
        RESUME,
        COMPLETE,
        APPROVE,
        REJECT,
        CANCEL
    }

    /** 个人待办按执行责任与跟进责任分开；不改变原查询省略时的行为。 */
    public enum PersonalScope {
        ACTION,
        FOLLOW_UP
    }

    public enum DataMode {
        INDEPENDENT,
        SHARED
    }

    public enum Urgency {
        NORMAL,
        URGENT
    }

    public enum Priority {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum Category {
        PROJECT,
        DAILY
    }

    /** 旧接口与存量数据的兼容标签；所有任务使用同一 DAG，不据此决定功能或节点身份。 */
    public enum Kind {
        ORDINARY,
        PROCESS
    }

    /** 仅描述实例层级位置：根、根直属节点、更深层的子任务。 */
    public enum NodeRole {
        ROOT,
        NODE,
        SUBTASK
    }

    public enum EventType {
        CREATED,
        STARTED,
        PAUSED,
        RESUMED,
        COMPLETED,
        SUBMITTED_FOR_ACCEPTANCE,
        ACCEPTED,
        REJECTED,
        CANCELLED,
        COMMENTED,
        PLANNED,
        BUSINESS_SAVED,
        ADJUSTED,
        SUBTASK_DELETED,
        LINKED,
        UNLINKED,
        CLAIMED,
        ASSIGNED,
        ROLLUP_BLOCKED
    }

    /** 服务端记录节点创建来源，不接受客户端自报来源以获取删除权限。 */
    public enum CreationOrigin {
        ARRANGED,
        PERSONAL_SPLIT
    }

    public record CreationEvidence(CreationOrigin origin, String parentId) {}

    /** 删除仅针对本人拆分的一个未执行叶子节点；修订取当前节点 revision。 */
    public record DeleteSubtask(String id, int expectedRevision, String requestKey) {}

    /** 删除审计保留稳定身份，挂在未删除的上级节点，不能删除业务数据。 */
    public record DeletedSubtask(String id, String title, String parentId) {}

    /** 引用普通业务记录；label 仅供显示，权限和过滤必须使用稳定身份。 */
    public record RecordRef(String applicationId, String objectId, String recordId, String label) {}

    /** viewId 固定业务视图；历史只绑定表单的任务保留原权限口径。 */
    public record Binding(String applicationId, String formId, String entryId, String viewId) {
        public Binding(String applicationId, String formId, String entryId) {
            this(applicationId, formId, entryId, null);
        }
    }

    public record EntryOption(String value, String label) {}

    public record Schedule(
            TimeMode mode,
            LocalDateTime fixedStart,
            int offsetDays,
            int durationDays,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime fixedEnd) {
        public Schedule(TimeMode mode, LocalDateTime fixedStart, int offsetDays, int durationDays) {
            this(mode, fixedStart, offsetDays, durationDays, null);
        }
    }

    /** 自动计算与历史汇总只描述日期来源，不改变任务执行权限。 */
    public enum ScheduleSource {
        AUTO,
        ROLLUP,
        EXPLICIT,
        UNSCHEDULED
    }

    /** partial 表示本节点或下级仍有未排完整的日期；提示不包含隐藏节点的身份信息。 */
    public record ScheduleSummary(ScheduleSource source, boolean partial, List<String> warnings) {}

    /** 发起预览只接受客户端当前草稿，不通过节点 ID 读取任何已存在实例。 */
    public record SchedulePreviewQuery(List<NodeInput> nodes, LocalDateTime plannedStart) {}

    public record SchedulePreviewNode(
            String id,
            String title,
            LocalDateTime expectedStart,
            LocalDateTime expectedEnd,
            boolean partial,
            List<String> warnings) {}

    public record SchedulePreview(List<SchedulePreviewNode> nodes, List<String> warnings) {}

    public record Sharing(DataMode mode, String sourceNodeId, List<String> writableFieldIds) {}

    /** 显式总任务数据授权；缺省保持旧版成员权限交集，不能由子节点单独扩大。 */
    public enum DataAccessMode {
        GROUP,
        ALL
    }

    public record DataPolicy(int version, DataAccessMode business, DataAccessMode feedback) {}

    // 实例显示序号由服务端管理，业务节点契约不接收客户端提供的排序元数据。
    @JsonIgnoreProperties("displayOrder")
    public record NodeInput(
            String id,
            String parentId,
            String title,
            String description,
            Long assigneeId,
            Urgency urgency,
            Priority priority,
            Schedule schedule,
            List<String> predecessorIds,
            Binding binding,
            Sharing sharing,
            List<TaskWorkEntries.Config> entries,
            @JsonInclude(JsonInclude.Include.NON_NULL) AssignmentMode assignmentMode,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Long> candidateUserIds,
            @JsonInclude(JsonInclude.Include.NON_NULL) DataPolicy dataPolicy,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long acceptorId,
            // 总任务参考工作量，单位分钟；空或 0 表示未设置，不影响日历排期。
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer effectiveWorkMinutes,
            @JsonInclude(JsonInclude.Include.NON_NULL) TaskWorkTimes.TotalMode workTotalMode) {
        /** 旧工时请求默认为手工预算，不把历史参考值隐式重算。 */
        public NodeInput(
                String id,
                String parentId,
                String title,
                String description,
                Long assigneeId,
                Urgency urgency,
                Priority priority,
                Schedule schedule,
                List<String> predecessorIds,
                Binding binding,
                Sharing sharing,
                List<TaskWorkEntries.Config> entries,
                AssignmentMode assignmentMode,
                List<Long> candidateUserIds,
                DataPolicy dataPolicy,
                Long acceptorId,
                Integer effectiveWorkMinutes) {
            this(
                    id,
                    parentId,
                    title,
                    description,
                    assigneeId,
                    urgency,
                    priority,
                    schedule,
                    predecessorIds,
                    binding,
                    sharing,
                    entries,
                    assignmentMode,
                    candidateUserIds,
                    dataPolicy,
                    acceptorId,
                    effectiveWorkMinutes,
                    null);
        }

        /** 历史调用没有参考工时，保留原签名与空值语义。 */
        public NodeInput(
                String id,
                String parentId,
                String title,
                String description,
                Long assigneeId,
                Urgency urgency,
                Priority priority,
                Schedule schedule,
                List<String> predecessorIds,
                Binding binding,
                Sharing sharing,
                List<TaskWorkEntries.Config> entries,
                AssignmentMode assignmentMode,
                List<Long> candidateUserIds,
                DataPolicy dataPolicy,
                Long acceptorId) {
            this(
                    id,
                    parentId,
                    title,
                    description,
                    assigneeId,
                    urgency,
                    priority,
                    schedule,
                    predecessorIds,
                    binding,
                    sharing,
                    entries,
                    assignmentMode,
                    candidateUserIds,
                    dataPolicy,
                    acceptorId,
                    null);
        }

        /** 旧调用没有验收环节，继续由负责人直接完成。 */
        public NodeInput(
                String id,
                String parentId,
                String title,
                String description,
                Long assigneeId,
                Urgency urgency,
                Priority priority,
                Schedule schedule,
                List<String> predecessorIds,
                Binding binding,
                Sharing sharing,
                List<TaskWorkEntries.Config> entries,
                AssignmentMode assignmentMode,
                List<Long> candidateUserIds,
                DataPolicy dataPolicy) {
            this(
                    id,
                    parentId,
                    title,
                    description,
                    assigneeId,
                    urgency,
                    priority,
                    schedule,
                    predecessorIds,
                    binding,
                    sharing,
                    entries,
                    assignmentMode,
                    candidateUserIds,
                    dataPolicy,
                    null);
        }

        public NodeInput(
                String id,
                String parentId,
                String title,
                String description,
                Long assigneeId,
                Urgency urgency,
                Priority priority,
                Schedule schedule,
                List<String> predecessorIds,
                Binding binding,
                Sharing sharing,
                List<TaskWorkEntries.Config> entries,
                AssignmentMode assignmentMode,
                List<Long> candidateUserIds) {
            this(
                    id,
                    parentId,
                    title,
                    description,
                    assigneeId,
                    urgency,
                    priority,
                    schedule,
                    predecessorIds,
                    binding,
                    sharing,
                    entries,
                    assignmentMode,
                    candidateUserIds,
                    null);
        }

        public NodeInput(
                String id,
                String parentId,
                String title,
                String description,
                Long assigneeId,
                Urgency urgency,
                Priority priority,
                Schedule schedule,
                List<String> predecessorIds,
                Binding binding,
                Sharing sharing,
                List<TaskWorkEntries.Config> entries) {
            this(
                    id,
                    parentId,
                    title,
                    description,
                    assigneeId,
                    urgency,
                    priority,
                    schedule,
                    predecessorIds,
                    binding,
                    sharing,
                    entries,
                    null,
                    null);
        }

        public NodeInput(
                String id,
                String parentId,
                String title,
                String description,
                Long assigneeId,
                Urgency urgency,
                Priority priority,
                Schedule schedule,
                List<String> predecessorIds,
                Binding binding,
                Sharing sharing) {
            this(
                    id,
                    parentId,
                    title,
                    description,
                    assigneeId,
                    urgency,
                    priority,
                    schedule,
                    predecessorIds,
                    binding,
                    sharing,
                    null);
        }
    }

    public record BusinessRef(
            PublishedResourceRef resource,
            ApplicationCenter.ObjectReference object,
            String recordId,
            String requestId) {}

    public record Plan(
            Period period,
            LocalDate date,
            long userId,
            long arrangedById,
            String arrangedByName,
            String source,
            LocalDateTime arrangedAt,
            String id,
            LocalDate endDate,
            boolean active,
            String historyReason,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean canCancel,
            @JsonInclude(JsonInclude.Include.NON_NULL) String userName,
            TaskPlanning.Mode mode,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean inherited,
            @JsonInclude(JsonInclude.Include.NON_NULL) String inheritedFromTaskId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String inheritedFromTitle) {
        /** 继承项是只读投影，id 为空；来源不可见时只返回 inherited，不暴露上级标识和标题。 */
        public Plan(
                Period period,
                LocalDate date,
                long userId,
                long arrangedById,
                String arrangedByName,
                String source,
                LocalDateTime arrangedAt,
                String id,
                LocalDate endDate,
                boolean active,
                String historyReason,
                Boolean canCancel,
                String userName,
                TaskPlanning.Mode mode) {
            this(
                    period,
                    date,
                    userId,
                    arrangedById,
                    arrangedByName,
                    source,
                    arrangedAt,
                    id,
                    endDate,
                    active,
                    historyReason,
                    canCancel,
                    userName,
                    mode,
                    null,
                    null,
                    null);
        }

        public Plan(
                Period period,
                LocalDate date,
                long userId,
                long arrangedById,
                String arrangedByName,
                String source,
                LocalDateTime arrangedAt,
                String id,
                LocalDate endDate,
                boolean active,
                String historyReason,
                Boolean canCancel,
                String userName) {
            this(
                    period,
                    date,
                    userId,
                    arrangedById,
                    arrangedByName,
                    source,
                    arrangedAt,
                    id,
                    endDate,
                    active,
                    historyReason,
                    canCancel,
                    userName,
                    TaskPlanning.Mode.SCHEDULE);
        }

        public Plan(
                Period period,
                LocalDate date,
                long userId,
                long arrangedById,
                String arrangedByName,
                String source,
                LocalDateTime arrangedAt) {
            this(
                    period,
                    date,
                    userId,
                    arrangedById,
                    arrangedByName,
                    source,
                    arrangedAt,
                    null,
                    date,
                    true,
                    null,
                    null,
                    null);
        }

        public Plan(Period period, LocalDate date) {
            this(period, date, 0, 0, null, "SELF", null);
        }
    }

    /** 真实上级链的只读位置摘要；detailVisible 不授予开始、完成或业务数据权限。 */
    public record AncestorContext(
            String id,
            String parentId,
            String title,
            String assigneeName,
            String status,
            boolean detailVisible) {}

    public record Row(
            String id,
            String rootId,
            String parentId,
            String title,
            String description,
            String status,
            long creatorId,
            String creatorName,
            Long assigneeId,
            String assigneeName,
            RecordRef project,
            Urgency urgency,
            Priority priority,
            Schedule schedule,
            List<String> predecessorIds,
            Binding binding,
            Sharing sharing,
            BusinessRef business,
            LocalDateTime baselineStart,
            LocalDateTime baselineEnd,
            LocalDateTime expectedStart,
            LocalDateTime expectedEnd,
            LocalDateTime actualStart,
            LocalDateTime actualEnd,
            LocalDateTime createdAt,
            int revision,
            int instanceRevision,
            int childCount,
            List<Plan> plans,
            boolean canStart,
            boolean canExecute,
            boolean canEdit,
            String blockedReason,
            String templateId,
            Integer templateVersion,
            boolean canPlan,
            String explicitLinkId,
            boolean canUnlink,
            LocalDateTime lastHandledAt,
            Kind kind,
            NodeRole role,
            List<TaskWorkEntries.Config> entries,
            String applicationId,
            AssignmentMode assignmentMode,
            List<Long> candidateUserIds,
            boolean canClaim,
            boolean canAssign,
            LocalDateTime plannedStart,
            boolean legacyProtocol,
            DataPolicy dataPolicy,
            @JsonInclude(JsonInclude.Include.NON_NULL) String groupStatus,
            List<AncestorContext> ancestorContext,
            Long acceptorId,
            String acceptorName,
            boolean canAccept,
            boolean canDelegate,
            String completionReason,
            // 仅总任务返回创建时冻结的参考工时，子任务与历史任务为空。
            Integer effectiveWorkMinutes,
            boolean canPause,
            boolean canResume,
            @JsonInclude(JsonInclude.Include.NON_NULL) String pausedByTaskId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String pauseReason,
            boolean canDelete,
            @JsonInclude(JsonInclude.Include.NON_NULL) String deleteBlockedReason,
            // 与 childCount 同口径：仅当前操作者可见的直属下级中已完成的数量。
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer completedChildCount,
            ScheduleSummary scheduleSummary,
            boolean canTransfer) {
        /** 兼容未提供在办转交权限的投影，旧调用不授予交接能力。 */
        public Row(
                String id,
                String rootId,
                String parentId,
                String title,
                String description,
                String status,
                long creatorId,
                String creatorName,
                Long assigneeId,
                String assigneeName,
                RecordRef project,
                Urgency urgency,
                Priority priority,
                Schedule schedule,
                List<String> predecessorIds,
                Binding binding,
                Sharing sharing,
                BusinessRef business,
                LocalDateTime baselineStart,
                LocalDateTime baselineEnd,
                LocalDateTime expectedStart,
                LocalDateTime expectedEnd,
                LocalDateTime actualStart,
                LocalDateTime actualEnd,
                LocalDateTime createdAt,
                int revision,
                int instanceRevision,
                int childCount,
                List<Plan> plans,
                boolean canStart,
                boolean canExecute,
                boolean canEdit,
                String blockedReason,
                String templateId,
                Integer templateVersion,
                boolean canPlan,
                String explicitLinkId,
                boolean canUnlink,
                LocalDateTime lastHandledAt,
                Kind kind,
                NodeRole role,
                List<TaskWorkEntries.Config> entries,
                String applicationId,
                AssignmentMode assignmentMode,
                List<Long> candidateUserIds,
                boolean canClaim,
                boolean canAssign,
                LocalDateTime plannedStart,
                boolean legacyProtocol,
                DataPolicy dataPolicy,
                String groupStatus,
                List<AncestorContext> ancestorContext,
                Long acceptorId,
                String acceptorName,
                boolean canAccept,
                boolean canDelegate,
                String completionReason,
                Integer effectiveWorkMinutes,
                boolean canPause,
                boolean canResume,
                String pausedByTaskId,
                String pauseReason,
                boolean canDelete,
                String deleteBlockedReason,
                Integer completedChildCount,
                ScheduleSummary scheduleSummary) {
            this(
                    id,
                    rootId,
                    parentId,
                    title,
                    description,
                    status,
                    creatorId,
                    creatorName,
                    assigneeId,
                    assigneeName,
                    project,
                    urgency,
                    priority,
                    schedule,
                    predecessorIds,
                    binding,
                    sharing,
                    business,
                    baselineStart,
                    baselineEnd,
                    expectedStart,
                    expectedEnd,
                    actualStart,
                    actualEnd,
                    createdAt,
                    revision,
                    instanceRevision,
                    childCount,
                    plans,
                    canStart,
                    canExecute,
                    canEdit,
                    blockedReason,
                    templateId,
                    templateVersion,
                    canPlan,
                    explicitLinkId,
                    canUnlink,
                    lastHandledAt,
                    kind,
                    role,
                    entries,
                    applicationId,
                    assignmentMode,
                    candidateUserIds,
                    canClaim,
                    canAssign,
                    plannedStart,
                    legacyProtocol,
                    dataPolicy,
                    groupStatus,
                    ancestorContext,
                    acceptorId,
                    acceptorName,
                    canAccept,
                    canDelegate,
                    completionReason,
                    effectiveWorkMinutes,
                    canPause,
                    canResume,
                    pausedByTaskId,
                    pauseReason,
                    canDelete,
                    deleteBlockedReason,
                    completedChildCount,
                    scheduleSummary,
                    false);
        }

        /** 保留未提供排期摘要的旧投影构造。 */
        public Row(
                String id,
                String rootId,
                String parentId,
                String title,
                String description,
                String status,
                long creatorId,
                String creatorName,
                Long assigneeId,
                String assigneeName,
                RecordRef project,
                Urgency urgency,
                Priority priority,
                Schedule schedule,
                List<String> predecessorIds,
                Binding binding,
                Sharing sharing,
                BusinessRef business,
                LocalDateTime baselineStart,
                LocalDateTime baselineEnd,
                LocalDateTime expectedStart,
                LocalDateTime expectedEnd,
                LocalDateTime actualStart,
                LocalDateTime actualEnd,
                LocalDateTime createdAt,
                int revision,
                int instanceRevision,
                int childCount,
                List<Plan> plans,
                boolean canStart,
                boolean canExecute,
                boolean canEdit,
                String blockedReason,
                String templateId,
                Integer templateVersion,
                boolean canPlan,
                String explicitLinkId,
                boolean canUnlink,
                LocalDateTime lastHandledAt,
                Kind kind,
                NodeRole role,
                List<TaskWorkEntries.Config> entries,
                String applicationId,
                AssignmentMode assignmentMode,
                List<Long> candidateUserIds,
                boolean canClaim,
                boolean canAssign,
                LocalDateTime plannedStart,
                boolean legacyProtocol,
                DataPolicy dataPolicy,
                String groupStatus,
                List<AncestorContext> ancestorContext,
                Long acceptorId,
                String acceptorName,
                boolean canAccept,
                boolean canDelegate,
                String completionReason,
                Integer effectiveWorkMinutes,
                boolean canPause,
                boolean canResume,
                String pausedByTaskId,
                String pauseReason,
                boolean canDelete,
                String deleteBlockedReason,
                Integer completedChildCount) {
            this(
                    id,
                    rootId,
                    parentId,
                    title,
                    description,
                    status,
                    creatorId,
                    creatorName,
                    assigneeId,
                    assigneeName,
                    project,
                    urgency,
                    priority,
                    schedule,
                    predecessorIds,
                    binding,
                    sharing,
                    business,
                    baselineStart,
                    baselineEnd,
                    expectedStart,
                    expectedEnd,
                    actualStart,
                    actualEnd,
                    createdAt,
                    revision,
                    instanceRevision,
                    childCount,
                    plans,
                    canStart,
                    canExecute,
                    canEdit,
                    blockedReason,
                    templateId,
                    templateVersion,
                    canPlan,
                    explicitLinkId,
                    canUnlink,
                    lastHandledAt,
                    kind,
                    role,
                    entries,
                    applicationId,
                    assignmentMode,
                    candidateUserIds,
                    canClaim,
                    canAssign,
                    plannedStart,
                    legacyProtocol,
                    dataPolicy,
                    groupStatus,
                    ancestorContext,
                    acceptorId,
                    acceptorName,
                    canAccept,
                    canDelegate,
                    completionReason,
                    effectiveWorkMinutes,
                    canPause,
                    canResume,
                    pausedByTaskId,
                    pauseReason,
                    canDelete,
                    deleteBlockedReason,
                    completedChildCount,
                    null,
                    false);
        }

        /** 兼容新增暂停能力前的参考工时投影，缺省不授予暂停权限。 */
        public Row(
                String id,
                String rootId,
                String parentId,
                String title,
                String description,
                String status,
                long creatorId,
                String creatorName,
                Long assigneeId,
                String assigneeName,
                RecordRef project,
                Urgency urgency,
                Priority priority,
                Schedule schedule,
                List<String> predecessorIds,
                Binding binding,
                Sharing sharing,
                BusinessRef business,
                LocalDateTime baselineStart,
                LocalDateTime baselineEnd,
                LocalDateTime expectedStart,
                LocalDateTime expectedEnd,
                LocalDateTime actualStart,
                LocalDateTime actualEnd,
                LocalDateTime createdAt,
                int revision,
                int instanceRevision,
                int childCount,
                List<Plan> plans,
                boolean canStart,
                boolean canExecute,
                boolean canEdit,
                String blockedReason,
                String templateId,
                Integer templateVersion,
                boolean canPlan,
                String explicitLinkId,
                boolean canUnlink,
                LocalDateTime lastHandledAt,
                Kind kind,
                NodeRole role,
                List<TaskWorkEntries.Config> entries,
                String applicationId,
                AssignmentMode assignmentMode,
                List<Long> candidateUserIds,
                boolean canClaim,
                boolean canAssign,
                LocalDateTime plannedStart,
                boolean legacyProtocol,
                DataPolicy dataPolicy,
                String groupStatus,
                List<AncestorContext> ancestorContext,
                Long acceptorId,
                String acceptorName,
                boolean canAccept,
                boolean canDelegate,
                String completionReason,
                Integer effectiveWorkMinutes) {
            this(
                    id,
                    rootId,
                    parentId,
                    title,
                    description,
                    status,
                    creatorId,
                    creatorName,
                    assigneeId,
                    assigneeName,
                    project,
                    urgency,
                    priority,
                    schedule,
                    predecessorIds,
                    binding,
                    sharing,
                    business,
                    baselineStart,
                    baselineEnd,
                    expectedStart,
                    expectedEnd,
                    actualStart,
                    actualEnd,
                    createdAt,
                    revision,
                    instanceRevision,
                    childCount,
                    plans,
                    canStart,
                    canExecute,
                    canEdit,
                    blockedReason,
                    templateId,
                    templateVersion,
                    canPlan,
                    explicitLinkId,
                    canUnlink,
                    lastHandledAt,
                    kind,
                    role,
                    entries,
                    applicationId,
                    assignmentMode,
                    candidateUserIds,
                    canClaim,
                    canAssign,
                    plannedStart,
                    legacyProtocol,
                    dataPolicy,
                    groupStatus,
                    ancestorContext,
                    acceptorId,
                    acceptorName,
                    canAccept,
                    canDelegate,
                    completionReason,
                    null,
                    false,
                    false,
                    null,
                    null,
                    false,
                    null,
                    null);
        }

        /** 兼容旧的任务投影构造，历史实例未记录参考工时。 */
        public Row(
                String id,
                String rootId,
                String parentId,
                String title,
                String description,
                String status,
                long creatorId,
                String creatorName,
                Long assigneeId,
                String assigneeName,
                RecordRef project,
                Urgency urgency,
                Priority priority,
                Schedule schedule,
                List<String> predecessorIds,
                Binding binding,
                Sharing sharing,
                BusinessRef business,
                LocalDateTime baselineStart,
                LocalDateTime baselineEnd,
                LocalDateTime expectedStart,
                LocalDateTime expectedEnd,
                LocalDateTime actualStart,
                LocalDateTime actualEnd,
                LocalDateTime createdAt,
                int revision,
                int instanceRevision,
                int childCount,
                List<Plan> plans,
                boolean canStart,
                boolean canExecute,
                boolean canEdit,
                String blockedReason,
                String templateId,
                Integer templateVersion,
                boolean canPlan,
                String explicitLinkId,
                boolean canUnlink,
                LocalDateTime lastHandledAt,
                Kind kind,
                NodeRole role,
                List<TaskWorkEntries.Config> entries,
                String applicationId,
                AssignmentMode assignmentMode,
                List<Long> candidateUserIds,
                boolean canClaim,
                boolean canAssign,
                LocalDateTime plannedStart,
                boolean legacyProtocol,
                DataPolicy dataPolicy,
                String groupStatus,
                List<AncestorContext> ancestorContext,
                Long acceptorId,
                String acceptorName,
                boolean canAccept,
                boolean canDelegate,
                String completionReason) {
            this(
                    id,
                    rootId,
                    parentId,
                    title,
                    description,
                    status,
                    creatorId,
                    creatorName,
                    assigneeId,
                    assigneeName,
                    project,
                    urgency,
                    priority,
                    schedule,
                    predecessorIds,
                    binding,
                    sharing,
                    business,
                    baselineStart,
                    baselineEnd,
                    expectedStart,
                    expectedEnd,
                    actualStart,
                    actualEnd,
                    createdAt,
                    revision,
                    instanceRevision,
                    childCount,
                    plans,
                    canStart,
                    canExecute,
                    canEdit,
                    blockedReason,
                    templateId,
                    templateVersion,
                    canPlan,
                    explicitLinkId,
                    canUnlink,
                    lastHandledAt,
                    kind,
                    role,
                    entries,
                    applicationId,
                    assignmentMode,
                    candidateUserIds,
                    canClaim,
                    canAssign,
                    plannedStart,
                    legacyProtocol,
                    dataPolicy,
                    groupStatus,
                    ancestorContext,
                    acceptorId,
                    acceptorName,
                    canAccept,
                    canDelegate,
                    completionReason,
                    null,
                    false,
                    false,
                    null,
                    null,
                    false,
                    null,
                    null);
        }
    }

    public record Query(
            String scope,
            String tab,
            LocalDate date,
            String search,
            String category,
            RecordRef project,
            String status,
            Urgency urgency,
            Priority priority,
            String entryId,
            LocalDate from,
            LocalDate to,
            int pageNo,
            int pageSize,
            Period recentPeriod,
            Kind kind,
            @JsonInclude(JsonInclude.Include.NON_NULL) AssignmentMode assignmentMode,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean rootsOnly,
            @JsonInclude(JsonInclude.Include.NON_NULL) TaskPlanning.Scope scheduleScope,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long assigneeId,
            @JsonInclude(JsonInclude.Include.NON_NULL) TaskPlanning.Filter planFilter,
            @JsonInclude(JsonInclude.Include.NON_NULL) TaskPlanning.Mode planMode,
            @JsonInclude(JsonInclude.Include.NON_NULL) PersonalScope personalScope) {
        public Query(
                String scope,
                String tab,
                LocalDate date,
                String search,
                String category,
                RecordRef project,
                String status,
                Urgency urgency,
                Priority priority,
                String entryId,
                LocalDate from,
                LocalDate to,
                int pageNo,
                int pageSize,
                Period recentPeriod,
                Kind kind,
                AssignmentMode assignmentMode,
                Boolean rootsOnly,
                TaskPlanning.Scope scheduleScope,
                Long assigneeId,
                TaskPlanning.Filter planFilter,
                TaskPlanning.Mode planMode) {
            this(
                    scope,
                    tab,
                    date,
                    search,
                    category,
                    project,
                    status,
                    urgency,
                    priority,
                    entryId,
                    from,
                    to,
                    pageNo,
                    pageSize,
                    recentPeriod,
                    kind,
                    assignmentMode,
                    rootsOnly,
                    scheduleScope,
                    assigneeId,
                    planFilter,
                    planMode,
                    null);
        }

        public Query(
                String scope,
                String tab,
                LocalDate date,
                String search,
                String category,
                RecordRef project,
                String status,
                Urgency urgency,
                Priority priority,
                String entryId,
                LocalDate from,
                LocalDate to,
                int pageNo,
                int pageSize,
                Period recentPeriod,
                Kind kind,
                AssignmentMode assignmentMode,
                Boolean rootsOnly,
                TaskPlanning.Scope scheduleScope,
                Long assigneeId,
                TaskPlanning.Filter planFilter) {
            this(
                    scope,
                    tab,
                    date,
                    search,
                    category,
                    project,
                    status,
                    urgency,
                    priority,
                    entryId,
                    from,
                    to,
                    pageNo,
                    pageSize,
                    recentPeriod,
                    kind,
                    assignmentMode,
                    rootsOnly,
                    scheduleScope,
                    assigneeId,
                    planFilter,
                    null);
        }

        public Query(
                String scope,
                String tab,
                LocalDate date,
                String search,
                String category,
                RecordRef project,
                String status,
                Urgency urgency,
                Priority priority,
                String entryId,
                LocalDate from,
                LocalDate to,
                int pageNo,
                int pageSize,
                Period recentPeriod,
                Kind kind,
                AssignmentMode assignmentMode,
                Boolean rootsOnly) {
            this(
                    scope,
                    tab,
                    date,
                    search,
                    category,
                    project,
                    status,
                    urgency,
                    priority,
                    entryId,
                    from,
                    to,
                    pageNo,
                    pageSize,
                    recentPeriod,
                    kind,
                    assignmentMode,
                    rootsOnly,
                    null,
                    null,
                    null);
        }

        /** 旧查询保持逐节点分页；整体分页须由任务管理显式开启。 */
        public Query(
                String scope,
                String tab,
                LocalDate date,
                String search,
                String category,
                RecordRef project,
                String status,
                Urgency urgency,
                Priority priority,
                String entryId,
                LocalDate from,
                LocalDate to,
                int pageNo,
                int pageSize,
                Period recentPeriod,
                Kind kind,
                AssignmentMode assignmentMode) {
            this(
                    scope,
                    tab,
                    date,
                    search,
                    category,
                    project,
                    status,
                    urgency,
                    priority,
                    entryId,
                    from,
                    to,
                    pageNo,
                    pageSize,
                    recentPeriod,
                    kind,
                    assignmentMode,
                    null);
        }

        public Query(
                String scope,
                String tab,
                LocalDate date,
                String search,
                String category,
                RecordRef project,
                String status,
                Urgency urgency,
                Priority priority,
                String entryId,
                LocalDate from,
                LocalDate to,
                int pageNo,
                int pageSize,
                Period recentPeriod,
                Kind kind) {
            this(
                    scope,
                    tab,
                    date,
                    search,
                    category,
                    project,
                    status,
                    urgency,
                    priority,
                    entryId,
                    from,
                    to,
                    pageNo,
                    pageSize,
                    recentPeriod,
                    kind,
                    null);
        }

        public Query(
                String scope,
                String tab,
                LocalDate date,
                String search,
                String category,
                RecordRef project,
                String status,
                Urgency urgency,
                Priority priority,
                String entryId,
                LocalDate from,
                LocalDate to,
                int pageNo,
                int pageSize,
                Period recentPeriod) {
            this(
                    scope,
                    tab,
                    date,
                    search,
                    category,
                    project,
                    status,
                    urgency,
                    priority,
                    entryId,
                    from,
                    to,
                    pageNo,
                    pageSize,
                    recentPeriod,
                    null);
        }

        public Query(
                String scope,
                String tab,
                LocalDate date,
                String search,
                String category,
                RecordRef project,
                String status,
                Urgency urgency,
                Priority priority,
                String entryId,
                LocalDate from,
                LocalDate to,
                int pageNo,
                int pageSize) {
            this(
                    scope,
                    tab,
                    date,
                    search,
                    category,
                    project,
                    status,
                    urgency,
                    priority,
                    entryId,
                    from,
                    to,
                    pageNo,
                    pageSize,
                    Period.DAY);
        }
    }

    /** 本人筛选决定入组，展开保留整组结构；结构摘要与详情权限、当前筛选分别判断。 */
    public record PersonalTreeNode(
            Row task,
            int matchingChildCount,
            boolean contextOnly,
            boolean detailVisible,
            int myPendingCount,
            int myCompletedCount,
            int completedChildCount,
            StructureNode structure,
            String anchorTaskId) {
        public PersonalTreeNode(
                Row task,
                int matchingChildCount,
                boolean contextOnly,
                boolean detailVisible,
                int myPendingCount,
                int myCompletedCount,
                int completedChildCount) {
            this(
                    task,
                    matchingChildCount,
                    contextOnly,
                    detailVisible,
                    myPendingCount,
                    myCompletedCount,
                    completedChildCount,
                    null,
                    task.id());
        }

        public PersonalTreeNode(Row task, int matchingChildCount) {
            this(task, matchingChildCount, false, true, 0, 0, 0);
        }
    }

    /** 展开时必须携带相同筛选条件，不能借父节点扩大个人清单范围。 */
    public record PersonalTreeChildren(Query query, String parentId) {}

    public record Ref(String id) {}

    public record MaterialRef(String taskId, String eventId) {}

    public record CompletionMaterial(
            String eventId,
            BusinessRef binding,
            ApplicationRecords.Model model,
            ApplicationRecords.Aggregate record,
            List<TaskWorkEntries.Material> entries) {
        public CompletionMaterial(
                String eventId,
                BusinessRef binding,
                ApplicationRecords.Model model,
                ApplicationRecords.Aggregate record) {
            this(eventId, binding, model, record, List.of());
        }
    }

    /** 领取前仅公开当前可领取任务的工作要求，不附带业务授权、私有任务树或操作记录。 */
    public record DetailPreview(
            String description,
            Priority priority,
            Schedule schedule,
            LocalDateTime expectedStart,
            LocalDateTime expectedEnd,
            LocalDateTime actualStart,
            LocalDateTime actualEnd,
            LocalDateTime createdAt,
            Long acceptorId,
            String acceptorName,
            Integer effectiveWorkMinutes,
            Integer templateVersion) {}

    /** 同组编排的只读结构摘要；状态包含祖先暂停，展示不授予内容、业务数据或操作权限。 */
    public record StructureNode(
            String id,
            String rootId,
            String parentId,
            String title,
            String status,
            String assigneeName,
            List<String> predecessorIds,
            LocalDateTime expectedStart,
            LocalDateTime expectedEnd,
            boolean detailVisible,
            ScheduleSummary scheduleSummary) {
        /** 保留未提供排期摘要的旧投影构造。 */
        public StructureNode(
                String id,
                String rootId,
                String parentId,
                String title,
                String status,
                String assigneeName,
                List<String> predecessorIds,
                LocalDateTime expectedStart,
                LocalDateTime expectedEnd,
                boolean detailVisible) {
            this(
                    id,
                    rootId,
                    parentId,
                    title,
                    status,
                    assigneeName,
                    predecessorIds,
                    expectedStart,
                    expectedEnd,
                    detailVisible,
                    null);
        }
    }

    public record Detail(
            Row task,
            List<Row> nodes,
            List<Comment> comments,
            List<Event> events,
            List<TaskRecordLink> links,
            @JsonInclude(JsonInclude.Include.NON_NULL) DetailPreview preview,
            List<StructureNode> structure) {
        /** 兼容旧详情及领取前预览；未明确提供结构时不额外公开同组节点。 */
        public Detail(
                Row task,
                List<Row> nodes,
                List<Comment> comments,
                List<Event> events,
                List<TaskRecordLink> links,
                DetailPreview preview) {
            this(task, nodes, comments, events, links, preview, List.of());
        }

        public Detail(
                Row task,
                List<Row> nodes,
                List<Comment> comments,
                List<Event> events,
                List<TaskRecordLink> links) {
            this(task, nodes, comments, events, links, null);
        }

        public Detail(Row task, List<Row> nodes, List<Comment> comments, List<Event> events) {
            this(task, nodes, comments, events, List.of());
        }
    }

    public record RecordContext(
            String applicationId, String pageId, String nodeId, String recordId) {}

    public record LinkTask(
            RecordContext context,
            String taskId,
            int expectedRevision,
            boolean include,
            String requestKey) {}

    public record TaskRecordLink(
            String id,
            RecordRef record,
            long creatorId,
            String creatorName,
            LocalDateTime createdAt) {}

    /**
     * 发起真实任务。指定模板时 nodes 为 null 沿用发布子图，非 null 为本次完整子图（空列表表示移除全部子任务）； 保留原节点设计 ID
     * 可追溯模板来源，新增节点不伪造来源，资源和数据授权仍受模板发布快照约束。
     */
    public record Create(
            NodeInput task,
            String parentId,
            String templateId,
            Integer templateVersion,
            RecordRef project,
            ApplicationRecords.Save business,
            RecordRef existingRecord,
            String requestKey,
            List<NodeInput> nodes,
            Kind kind,
            @JsonInclude(JsonInclude.Include.NON_NULL) String applicationId,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime plannedStart) {
        public Create(
                NodeInput task,
                String parentId,
                String templateId,
                Integer templateVersion,
                RecordRef project,
                ApplicationRecords.Save business,
                RecordRef existingRecord,
                String requestKey,
                List<NodeInput> nodes,
                Kind kind,
                String applicationId) {
            this(
                    task,
                    parentId,
                    templateId,
                    templateVersion,
                    project,
                    business,
                    existingRecord,
                    requestKey,
                    nodes,
                    kind,
                    applicationId,
                    null);
        }

        /** 应用归属可独立于主业务表单；旧调用缺省时从项目或主业务绑定推导。 */
        public Create(
                NodeInput task,
                String parentId,
                String templateId,
                Integer templateVersion,
                RecordRef project,
                ApplicationRecords.Save business,
                RecordRef existingRecord,
                String requestKey,
                List<NodeInput> nodes,
                Kind kind) {
            this(
                    task,
                    parentId,
                    templateId,
                    templateVersion,
                    project,
                    business,
                    existingRecord,
                    requestKey,
                    nodes,
                    kind,
                    null);
        }

        public Create(
                NodeInput task,
                String parentId,
                String templateId,
                Integer templateVersion,
                RecordRef project,
                ApplicationRecords.Save business,
                RecordRef existingRecord,
                String requestKey,
                List<NodeInput> nodes) {
            this(
                    task,
                    parentId,
                    templateId,
                    templateVersion,
                    project,
                    business,
                    existingRecord,
                    requestKey,
                    nodes,
                    null);
        }

        public Create(
                NodeInput task,
                String parentId,
                String templateId,
                Integer templateVersion,
                RecordRef project,
                ApplicationRecords.Save business,
                RecordRef existingRecord,
                String requestKey) {
            this(
                    task,
                    parentId,
                    templateId,
                    templateVersion,
                    project,
                    business,
                    existingRecord,
                    requestKey,
                    null);
        }
    }

    public record Transition(
            String id,
            int expectedRevision,
            Action action,
            String note,
            String requestKey,
            Boolean confirmCancelledChildren) {
        public Transition(
                String id, int expectedRevision, Action action, String note, String requestKey) {
            this(id, expectedRevision, action, note, requestKey, null);
        }
    }

    /** 本人原命令的只读确认；无回执且修订已向前推进才可释放待确认命令。 */
    public record TransitionRecovery(Detail applied, boolean superseded) {}

    public record Claim(String id, int expectedRevision, String requestKey) {}

    /** 省略 acceptance 保持原配置；显式空 acceptorId 表示取消验收环节。 */
    public record Acceptance(Long acceptorId) {}

    public record Assign(
            String id,
            int expectedRevision,
            AssignmentMode assignmentMode,
            Long assigneeId,
            List<Long> candidateUserIds,
            String requestKey,
            String note,
            @JsonInclude(JsonInclude.Include.NON_NULL) Acceptance acceptance,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean transfer) {
        /** 旧请求仍为未开始分配，不能因任务已开始而静默升级为交接。 */
        public Assign(
                String id,
                int expectedRevision,
                AssignmentMode assignmentMode,
                Long assigneeId,
                List<Long> candidateUserIds,
                String requestKey,
                String note,
                Acceptance acceptance) {
            this(
                    id,
                    expectedRevision,
                    assignmentMode,
                    assigneeId,
                    candidateUserIds,
                    requestKey,
                    note,
                    acceptance,
                    null);
        }

        public Assign(
                String id,
                int expectedRevision,
                AssignmentMode assignmentMode,
                Long assigneeId,
                List<Long> candidateUserIds,
                String requestKey,
                String note) {
            this(
                    id,
                    expectedRevision,
                    assignmentMode,
                    assigneeId,
                    candidateUserIds,
                    requestKey,
                    note,
                    null);
        }
    }

    /** 私有任务编排草稿，不创建任务、计划或业务记录。 */
    public record DraftSave(String id, Integer expectedRevision, Create content) {}

    public record DraftRef(String id, int expectedRevision) {}

    public record DraftPublish(String id, int expectedRevision, String requestKey) {}

    public record Draft(
            String id,
            int revision,
            LocalDateTime updatedAt,
            Create content,
            String publishedTaskId) {}

    public record DraftSummary(String id, int revision, String title, LocalDateTime updatedAt) {}

    public record SavePlan(
            List<String> ids, Period period, LocalDate date, boolean include, String target) {
        public SavePlan(List<String> ids, Period period, LocalDate date, boolean include) {
            this(ids, period, date, include, "SELF");
        }
    }

    public record Comment(
            String id,
            String taskId,
            String parentId,
            long authorId,
            String authorName,
            String content,
            List<Long> mentionedUserIds,
            LocalDateTime createdAt) {}

    public record AddComment(
            String taskId,
            String parentId,
            String content,
            List<Long> mentionedUserIds,
            String requestKey) {}

    public record Event(
            String id,
            String taskId,
            String type,
            long actorId,
            String actorName,
            String note,
            LocalDateTime createdAt) {}

    public record Member(long id, String name) {}

    public record FormContext(
            BusinessRef binding,
            ApplicationRecords.Model model,
            ApplicationUi.Form form,
            ApplicationRecords.Aggregate record,
            List<String> writableFieldIds,
            BusinessHandling.Result handling) {}

    public record SaveBusiness(
            String taskId, int expectedRevision, ApplicationRecords.Save record) {}

    /** publishedVersion 为最新发布序号；primaryVersion 为默认发起版本，切换主版本不覆盖草稿。 */
    public record Template(
            String id,
            String name,
            String description,
            int revision,
            Integer publishedVersion,
            List<NodeInput> nodes,
            long creatorId,
            LocalDateTime updatedAt,
            Kind kind,
            @JsonInclude(JsonInclude.Include.NON_NULL) NodeInput task,
            Integer primaryVersion) {
        public Template(
                String id,
                String name,
                String description,
                int revision,
                Integer publishedVersion,
                List<NodeInput> nodes,
                long creatorId,
                LocalDateTime updatedAt,
                Kind kind,
                NodeInput task) {
            this(
                    id,
                    name,
                    description,
                    revision,
                    publishedVersion,
                    nodes,
                    creatorId,
                    updatedAt,
                    kind,
                    task,
                    publishedVersion);
        }

        public Template(
                String id,
                String name,
                String description,
                int revision,
                Integer publishedVersion,
                List<NodeInput> nodes,
                long creatorId,
                LocalDateTime updatedAt,
                Kind kind) {
            this(
                    id,
                    name,
                    description,
                    revision,
                    publishedVersion,
                    nodes,
                    creatorId,
                    updatedAt,
                    kind,
                    null);
        }
    }

    public record SaveTemplate(
            String id,
            Integer expectedRevision,
            String name,
            String description,
            List<NodeInput> nodes,
            Kind kind,
            @JsonInclude(JsonInclude.Include.NON_NULL) NodeInput task) {
        public SaveTemplate(
                String id,
                Integer expectedRevision,
                String name,
                String description,
                List<NodeInput> nodes,
                Kind kind) {
            this(id, expectedRevision, name, description, nodes, kind, null);
        }

        public SaveTemplate(
                String id,
                Integer expectedRevision,
                String name,
                String description,
                List<NodeInput> nodes) {
            this(id, expectedRevision, name, description, nodes, null);
        }
    }

    /** setAsPrimary 省略时保持旧协议的发布即默认；首个版本始终成为主版本。 */
    public record PublishTemplate(String id, int expectedRevision, Boolean setAsPrimary) {
        public PublishTemplate(String id, int expectedRevision) {
            this(id, expectedRevision, null);
        }
    }

    /** 修改默认发起指针，不改写模板草稿、发布快照和历史任务实例。 */
    public record SetPrimaryTemplateVersion(String id, int version, int expectedRevision) {}

    /** 已发布版本列表按版本号倒序；nodeCount 仅统计子节点，与快照 nodes 口径一致。 */
    public record TemplateVersionSummary(
            int version,
            String name,
            String description,
            LocalDateTime publishedAt,
            int nodeCount,
            boolean primary) {}

    /** 模板实例按真实总任务分页；版本省略时包含所有发布版本，状态筛选总任务状态。 */
    public record TemplateInstances(
            String templateId,
            Integer version,
            String search,
            State status,
            int pageNo,
            int pageSize) {}

    /** root 为空表示仅有子任务详情权限；标题及状态沿用既有上级摘要，nodes 仅包含可见子任务。 */
    public record TemplateInstance(
            String rootId,
            String title,
            String status,
            Integer templateVersion,
            Row root,
            List<Row> nodes) {}

    public record TemplateVersion(
            String id,
            int version,
            String name,
            String description,
            List<NodeInput> nodes,
            LocalDateTime publishedAt,
            Kind kind,
            @JsonInclude(JsonInclude.Include.NON_NULL) NodeInput task) {
        public TemplateVersion(
                String id,
                int version,
                String name,
                String description,
                List<NodeInput> nodes,
                LocalDateTime publishedAt,
                Kind kind) {
            this(id, version, name, description, nodes, publishedAt, kind, null);
        }
    }

    public record Adjust(
            String rootId,
            int expectedRevision,
            List<NodeInput> nodes,
            String reason,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime plannedStart) {
        public Adjust(String rootId, int expectedRevision, List<NodeInput> nodes, String reason) {
            this(rootId, expectedRevision, nodes, reason, null);
        }
    }

    public record AdjustmentPreview(
            List<String> changedIds,
            List<String> addedIds,
            List<String> removedIds,
            List<String> affectedIds,
            @JsonInclude(JsonInclude.Include.NON_NULL) SchedulePreview schedule) {
        public AdjustmentPreview(
                List<String> changedIds,
                List<String> addedIds,
                List<String> removedIds,
                List<String> affectedIds) {
            this(changedIds, addedIds, removedIds, affectedIds, null);
        }
    }

    /** 配置页定位必须经发布 PAGE 与 FORM 解析，禁止客户端自行扩大全量范围。 */
    public record PageQuery(
            String applicationId,
            String pageId,
            String nodeId,
            String recordId,
            Query query,
            com.richuang.os.common.dto.DynamicConditionDTO conditions) {
        public PageQuery(
                String applicationId, String pageId, String nodeId, String recordId, Query query) {
            this(applicationId, pageId, nodeId, recordId, query, null);
        }
    }
}
