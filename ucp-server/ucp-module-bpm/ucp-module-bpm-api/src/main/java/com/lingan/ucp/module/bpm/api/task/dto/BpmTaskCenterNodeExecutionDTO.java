package com.lingan.ucp.module.bpm.api.task.dto;

/** 精确定位一次任务节点执行；bindingId 防止旧轮次完成事件误唤醒同名节点。 */
public record BpmTaskCenterNodeExecutionDTO(
        String executionId, String processInstanceId, String nodeId, String bindingId) {}
