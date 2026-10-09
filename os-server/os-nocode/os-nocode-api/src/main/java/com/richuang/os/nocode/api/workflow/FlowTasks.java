package com.richuang.os.nocode.api.workflow;

import com.richuang.os.nocode.api.SelectionFields;
import com.richuang.os.nocode.api.work.*;

import java.util.Map;

/** 节点配置由部署模型提供；办理请求只能提交当前任务、草稿修订与字段输入。 */
public final class FlowTasks {
    private FlowTasks() {}

    public static final String HANDLER = "nocode";

    /** 首批只支持 CREATE，禁止把未支持的动作当成新增执行。 */
    public record Configuration(PublishedResourceRef resource, String objectId, String operation) {}

    public record Context(
            String taskId,
            String processInstanceId,
            String nodeId,
            Configuration configuration,
            String draftId) {}

    /** 首次打开创建空草稿；重复打开恢复同一份草稿，完成后返回只读材料。 */
    public record Open(String taskId) {}

    public record Workspace(String processInstanceId, String nodeId, WorkDraftViews.Context work) {}

    public record Selection(String taskId, SelectionFields.Query query) {}

    public record Save(
            String taskId,
            String draftId,
            Integer expectedRevision,
            Map<String, Object> values,
            Map<String, java.util.List<com.richuang.os.nocode.api.ApplicationRecords.Row>>
                    details) {
        public Save(
                String taskId,
                String draftId,
                Integer expectedRevision,
                Map<String, Object> values) {
            this(taskId, draftId, expectedRevision, values, Map.of());
        }
    }

    public record Submit(
            String taskId,
            String draftId,
            int expectedRevision,
            String idempotencyKey,
            String reason,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String actionCode) {
        public Submit(
                String taskId,
                String draftId,
                int expectedRevision,
                String idempotencyKey,
                String reason) {
            this(taskId, draftId, expectedRevision, idempotencyKey, reason, null);
        }
    }
}
