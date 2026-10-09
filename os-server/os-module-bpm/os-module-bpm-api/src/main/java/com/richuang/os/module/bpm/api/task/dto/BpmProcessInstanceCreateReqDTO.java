package com.richuang.os.module.bpm.api.task.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 业务模块发起流程实例的请求。
 */
@Data
public class BpmProcessInstanceCreateReqDTO {

    private String processDefinitionId;

    private String processDefinitionKey;

    private Map<String, Object> variables;

    /**
     * 业务模块在流程发起前解析出的审批人快照，Key 为审批角色，Value 为用户 ID 列表。
     * <p>审批任务只允许使用该快照，避免流程运行期间业务配置变化影响已发起流程。</p>
     */
    private Map<String, List<Long>> approverSnapshot;

    @NotEmpty(message = "业务的唯一标识不能为空")
    private String businessKey;

    /**
     * 旧版发起人自选审批人配置，保留给既有 BPM 业务使用。
     */
    private Map<String, List<Long>> startUserSelectAssignees;

    /**
     * 普通业务可继续按 Key 发起，需要锁定版本的业务应传流程定义 ID。
     */
    @AssertTrue(message = "流程定义编号和标识不能同时为空")
    public boolean isProcessDefinitionConfigured() {
        return (processDefinitionId != null && !processDefinitionId.isBlank())
                || (processDefinitionKey != null && !processDefinitionKey.isBlank());
    }
}
