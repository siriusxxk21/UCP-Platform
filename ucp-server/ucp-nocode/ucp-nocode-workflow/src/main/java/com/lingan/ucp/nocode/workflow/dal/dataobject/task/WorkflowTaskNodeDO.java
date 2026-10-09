package com.lingan.ucp.nocode.workflow.dal.dataobject.task;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 一个引擎到达事实对应一个任务组，完成通知通过本表可靠重试。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_workflow_task_node", schema = "public")
public class WorkflowTaskNodeDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;

    private Long tenantId;
    private String executionId;
    private String processInstanceId;
    private String processDefinitionId;
    private String nodeId;
    private String nodeName;
    private Long initiatorId;
    private Long publisherId;
    private String configurationJson;
    private String peopleJson;
    private String taskId;
    private String state;
    private String lastError;
    private Integer attempts;
    private LocalDateTime nextAttemptTime;
}
