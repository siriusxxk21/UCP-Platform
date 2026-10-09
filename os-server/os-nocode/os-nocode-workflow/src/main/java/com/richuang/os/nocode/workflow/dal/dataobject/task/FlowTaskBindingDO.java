package com.richuang.os.nocode.workflow.dal.dataobject.task;

import com.baomidou.mybatisplus.annotation.*;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 不复制引擎任务状态；只保留任务固定身份、资源及本次提交证据。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_flow_task_binding", schema = "public")
public class FlowTaskBindingDO extends BaseDO {
    @TableId(value = "task_id", type = IdType.INPUT)
    private String taskId;

    private String taskJson;
    private String submissionId;
    private String submitter;
    private String requestDigest;
}
