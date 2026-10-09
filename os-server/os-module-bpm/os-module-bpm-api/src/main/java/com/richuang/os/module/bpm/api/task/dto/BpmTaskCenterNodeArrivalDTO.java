package com.richuang.os.module.bpm.api.task.dto;

import java.util.Map;

/** 引擎确认到达任务节点的事实；variables 仅包含配置引用的人员字段，不传递业务记录。 */
public record BpmTaskCenterNodeArrivalDTO(
        String executionId,
        String processInstanceId,
        String processDefinitionId,
        String nodeId,
        String nodeName,
        Long initiatorId,
        Long tenantId,
        String configurationJson,
        Map<String, Object> variables) {}
