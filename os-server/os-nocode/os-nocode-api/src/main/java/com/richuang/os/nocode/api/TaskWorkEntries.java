package com.richuang.os.nocode.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 执行任务的多个业务列表入口；入口身份和业务记录身份独立，授权沿用公共应用运行时。 */
public final class TaskWorkEntries {
    private TaskWorkEntries() {}

    public enum DataMode {
        ROOT_SHARED,
        INDEPENDENT,
        SOURCE_SHARED
    }

    public enum Operation {
        CREATED,
        UPDATED,
        LINKED,
        DELETED,
        UNCHANGED
    }

    public enum Category {
        BUSINESS,
        FEEDBACK
    }

    /** 标准工时是计量规则，不是开始至结束的自然时间。 */
    public enum WorkRuleMode {
        RECORD_ONCE,
        QUANTITY,
        CONDITION
    }

    /** 工时配置可省略；零时长兼容未配置占位。预计量只用于预算，省略不影响实际办理计量。 */
    public record WorkRule(
            WorkRuleMode mode,
            int minutes,
            String quantityFieldId,
            String conditionFieldId,
            Object conditionValue,
            Integer adjustmentMinutes,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal plannedQuantity) {
        /** 旧请求省略预计量时不产生新的 JSON 字段，保留收据哈希与历史预算。 */
        public WorkRule(
                WorkRuleMode mode,
                int minutes,
                String quantityFieldId,
                String conditionFieldId,
                Object conditionValue,
                Integer adjustmentMinutes) {
            this(
                    mode,
                    minutes,
                    quantityFieldId,
                    conditionFieldId,
                    conditionValue,
                    adjustmentMinutes,
                    null);
        }

        /** 旧模板没有调整值；基准始终保留，调整仅封存在本次任务实例。 */
        public WorkRule(
                WorkRuleMode mode,
                int minutes,
                String quantityFieldId,
                String conditionFieldId,
                Object conditionValue) {
            this(mode, minutes, quantityFieldId, conditionFieldId, conditionValue, null);
        }

        public long effectiveMinutes() {
            return (long) minutes + (adjustmentMinutes == null ? 0 : adjustmentMinutes);
        }
    }

    /** 总量只向任务创建人及管理者返回，普通执行人只接收本人计量。 */
    public record WorkSummary(
            java.math.BigDecimal myMinutes,
            int myRecordCount,
            java.math.BigDecimal totalMinutes,
            Integer totalRecordCount) {}

    public record Config(
            String key,
            String name,
            TaskCenter.Binding binding,
            DataMode dataMode,
            String sourceNodeId,
            String sourceEntryKey,
            List<String> readableFieldIds,
            List<String> writableFieldIds,
            boolean required,
            boolean allowAll,
            WorkRule workRule,
            TaskCenter.DataAccessMode dataScope) {
        /** dataScope 缺省时沿用整组历史策略或 allowAll；不改写旧发布版本。 */
        public Config(
                String key,
                String name,
                TaskCenter.Binding binding,
                DataMode dataMode,
                String sourceNodeId,
                String sourceEntryKey,
                List<String> readableFieldIds,
                List<String> writableFieldIds,
                boolean required,
                boolean allowAll,
                WorkRule workRule) {
            this(
                    key,
                    name,
                    binding,
                    dataMode,
                    sourceNodeId,
                    sourceEntryKey,
                    readableFieldIds,
                    writableFieldIds,
                    required,
                    allowAll,
                    workRule,
                    null);
        }

        public Config(
                String key,
                String name,
                TaskCenter.Binding binding,
                DataMode dataMode,
                String sourceNodeId,
                String sourceEntryKey,
                List<String> readableFieldIds,
                List<String> writableFieldIds,
                boolean required,
                boolean allowAll) {
            this(
                    key,
                    name,
                    binding,
                    dataMode,
                    sourceNodeId,
                    sourceEntryKey,
                    readableFieldIds,
                    writableFieldIds,
                    required,
                    allowAll,
                    null,
                    null);
        }
    }

    public record HandlingLocation(String taskId, String entryKey, String contributionId) {}

    public record Ref(String taskId, String entryKey) {}

    public record Query(
            String taskId,
            String entryKey,
            boolean all,
            boolean onlyMine,
            int pageNo,
            int pageSize,
            String search) {}

    public record Entry(
            Config config,
            TaskCenter.BusinessRef binding,
            String datasetId,
            boolean inherited,
            boolean canWrite,
            Integer contributionCount,
            boolean submitted,
            Category category,
            TaskCenter.DataAccessMode effectivePolicy,
            boolean canDelete,
            WorkSummary workSummary,
            String unavailableReason,
            boolean canLink) {
        /** 关联只引用当前可读记录，不等于新增或编辑；旧构造默认不宣称关联能力。 */
        public Entry(
                Config config,
                TaskCenter.BusinessRef binding,
                String datasetId,
                boolean inherited,
                boolean canWrite,
                Integer contributionCount,
                boolean submitted,
                Category category,
                TaskCenter.DataAccessMode effectivePolicy,
                boolean canDelete,
                WorkSummary workSummary,
                String unavailableReason) {
            this(
                    config,
                    binding,
                    datasetId,
                    inherited,
                    canWrite,
                    contributionCount,
                    submitted,
                    category,
                    effectivePolicy,
                    canDelete,
                    workSummary,
                    unavailableReason,
                    false);
        }

        /** 不可用时只返回任务已配置的入口信息，不返回业务模型、记录或工时。 */
        public Entry(
                Config config,
                TaskCenter.BusinessRef binding,
                String datasetId,
                boolean inherited,
                boolean canWrite,
                Integer contributionCount,
                boolean submitted,
                Category category,
                TaskCenter.DataAccessMode effectivePolicy,
                boolean canDelete,
                WorkSummary workSummary) {
            this(
                    config,
                    binding,
                    datasetId,
                    inherited,
                    canWrite,
                    contributionCount,
                    submitted,
                    category,
                    effectivePolicy,
                    canDelete,
                    workSummary,
                    null);
        }

        public Entry(
                Config config,
                TaskCenter.BusinessRef binding,
                String datasetId,
                boolean inherited,
                boolean canWrite,
                Integer contributionCount,
                boolean submitted,
                Category category,
                TaskCenter.DataAccessMode effectivePolicy,
                boolean canDelete) {
            this(
                    config,
                    binding,
                    datasetId,
                    inherited,
                    canWrite,
                    contributionCount,
                    submitted,
                    category,
                    effectivePolicy,
                    canDelete,
                    null);
        }

        public Entry(
                Config config,
                TaskCenter.BusinessRef binding,
                String datasetId,
                boolean inherited,
                boolean canWrite,
                Integer contributionCount,
                boolean submitted) {
            this(
                    config,
                    binding,
                    datasetId,
                    inherited,
                    canWrite,
                    contributionCount,
                    submitted,
                    Category.FEEDBACK,
                    null,
                    false);
        }
    }

    public record Source(
            String taskId,
            String taskTitle,
            long actorId,
            String actorName,
            String entryKey,
            String operation,
            String revision,
            LocalDateTime time) {}

    public record Item(
            String id,
            ApplicationRecords.Row record,
            String requestId,
            String status,
            List<Source> sources) {}

    public record Form(String taskId, String entryKey, String recordId, String contributionId) {}

    /** 求值只能发生在当前任务固定的表单与记录上下文内。 */
    public record FieldRules(
            Form target, com.richuang.os.nocode.api.FieldRules.EvaluateQuery query) {}

    public record RelatedFieldRules(Form target, RelatedForms.FieldRules query) {}

    public record Save(
            String taskId,
            String entryKey,
            String contributionId,
            ApplicationRecords.Save record) {}

    public record Link(String taskId, String entryKey, String recordId, String requestKey) {}

    public record Delete(
            String taskId,
            String entryKey,
            String recordId,
            String expectedRevision,
            String requestKey) {}

    public record Receipt(String taskId, String entryKey, String requestKey) {}

    /** 办理事实独立于当前业务列表；空入口查询当前任务及有权下级的全部资源。 */
    public record HistoryQuery(
            String taskId,
            String entryKey,
            boolean onlyCurrentTask,
            String recordId,
            Operation operation,
            String search,
            int pageNo,
            int pageSize) {}

    public record HistoryRef(String taskId, String entryKey, String contributionId) {}

    public record HandlingRow(
            String id,
            String taskId,
            String taskTitle,
            String entryKey,
            String entryName,
            Category category,
            String recordId,
            String recordLabel,
            Operation operation,
            long actorId,
            String actorName,
            LocalDateTime time,
            boolean detailAvailable,
            boolean historyKnown) {}

    /** beforeKnown/afterKnown 区分已知空值和没有采集的历史，不能把未知前值解释成新增。 */
    public record HandlingDetail(
            HandlingRow row,
            List<RecordHistory.Field> fields,
            Map<String, Object> before,
            Map<String, Object> after,
            List<RecordHistory.DetailChange> details,
            boolean beforeKnown,
            boolean afterKnown,
            boolean relatedUpdate) {}

    public record Saved(String contributionId, BusinessHandling.Result handling) {}

    public record Selection(Form target, SelectionFields.Query query) {}

    public record Fill(Form target, FormFills.Query query) {}

    public record Related(Form target, RelatedForms.Query query) {}

    public record RelatedSelection(Form target, RelatedForms.Selection query) {}

    public record RelatedFill(Form target, RelatedForms.Fill query) {}

    public record Submission(
            String contributionId,
            TaskCenter.BusinessRef binding,
            ApplicationRecords.Aggregate record,
            List<Source> sources) {}

    public record Material(
            String entryKey,
            String name,
            List<Item> records,
            List<Submission> submissions,
            ApplicationRecords.Model model,
            TaskCenter.BusinessRef binding) {
        public Material(
                String entryKey, String name, List<Item> records, List<Submission> submissions) {
            this(entryKey, name, records, submissions, null, null);
        }
    }
}
