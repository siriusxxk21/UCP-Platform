package com.lingan.ucp.nocode.api;

import java.time.LocalDateTime;
import java.util.Map;

/** 办理策略属于对象的业务操作，列表、表单及任务入口都必须遵守同一策略。 */
public final class BusinessHandling {
    private BusinessHandling() {}

    public record Policy(Rule create, Rule update) {}

    public record Rule(
            String mode,
            String processDefinitionId,
            DocumentPolicy.Expression condition,
            Map<String, String> variables) {
        public Rule {
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }
    }

    /** 提交申请与业务生效是两种结果，待审批不伪造业务记录 ID 或成功历史。 */
    public record Result(String outcome, ApplicationRecords.Aggregate result, Request request) {}

    public record Request(
            String id,
            int revision,
            String status,
            String applicationId,
            String applicationName,
            String objectId,
            String objectName,
            String entryId,
            String recordId,
            String operation,
            String name,
            String processInstanceId,
            String processDefinitionId,
            String submissionId,
            LocalDateTime submittedAt,
            LocalDateTime updatedAt,
            String error) {}

    public record Query(int pageNo, int pageSize, String status, String applicationId) {}

    public record Ref(String id) {}

    public record Withdraw(String id, int expectedRevision, String reason) {}

    public record Retry(String id, int expectedRevision) {}

    public record Receipt(String applicationId, String objectId, String requestKey) {}

    /** 不可变提交中的原始意图和变更前快照，不从流程变量还原写入命令。 */
    public record Material(
            ApplicationRecords.Save intent,
            ApplicationRecords.Aggregate before,
            Map<String, java.util.List<String>> relations) {
        public Material {
            relations = relations == null ? Map.of() : Map.copyOf(relations);
        }

        public Material(ApplicationRecords.Save intent, ApplicationRecords.Aggregate before) {
            this(intent, before, Map.of());
        }
    }

    public record Detail(
            Request request,
            com.lingan.ucp.nocode.api.work.WorkDrafts.Submission material,
            DataCenter.Definition definition) {}

    /** 重新填写生成新申请；旧申请及其封存材料保持不可变。 */
    public record Reopen(
            ApplicationRecords.Model model,
            ApplicationUi.Form form,
            ApplicationRecords.Aggregate initial,
            TaskEntries.Context entry,
            String formId) {}
}
