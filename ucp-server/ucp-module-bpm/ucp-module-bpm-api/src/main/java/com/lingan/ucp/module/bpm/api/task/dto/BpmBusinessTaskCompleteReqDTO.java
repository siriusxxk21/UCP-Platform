package com.lingan.ucp.module.bpm.api.task.dto;

import java.util.Map;

/**
 * 来源服务提交业务后调用的内部命令，不直接作为 HTTP 请求。
 *
 * <p>submissionId 仅是待核验的材料引用，不是授权凭证；Guard 仍须核对本次任务、操作者、材料及变量。
 */
public record BpmBusinessTaskCompleteReqDTO(
        String taskId, String submissionId, String reason, Map<String, Object> variables) {
    public BpmBusinessTaskCompleteReqDTO {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
