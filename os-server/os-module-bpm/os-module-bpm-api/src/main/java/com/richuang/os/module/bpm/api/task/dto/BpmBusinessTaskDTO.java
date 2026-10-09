package com.richuang.os.module.bpm.api.task.dto;

/** 引擎提供的当前任务身份；业务资源必须由来源模块按这些服务端标识解析。 */
public record BpmBusinessTaskDTO(
        String taskId,
        String processInstanceId,
        String processDefinitionId,
        String executionId,
        String taskDefinitionKey,
        String assignee,
        String handler,
        String configuration) {}
