package com.lingan.ucp.module.bpm.api.task.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 服务端验证当前审阅人后提供的部署、路径和提交事实；不接受客户端自报来源。 */
public record BpmMaterialReviewContextDTO(
        String processInstanceId,
        String processDefinitionId,
        String taskId,
        String tenantId,
        boolean active,
        boolean businessTask,
        String scope,
        String access,
        String blockedReason,
        List<Material> materials) {
    public record Material(
            String taskId,
            String nodeId,
            String nodeName,
            String submitterId,
            String submitterName,
            LocalDateTime submittedAt,
            BpmBusinessTaskDTO business,
            Form form,
            String warning) {}

    public record Form(String name, String conf, List<String> fields, Map<String, Object> values) {}
}
