package com.richuang.os.module.bpm.api.definition.dto;

/** 可发起或仍有运行实例的流程定义中固定业务节点引用，供业务资源生命周期预检。 */
public record BpmBusinessBindingDTO(
        String processDefinitionId,
        String processName,
        int processVersion,
        String nodeId,
        String nodeName,
        String configuration) {}
