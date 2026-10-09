package com.lingan.ucp.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 任务入口的固定发布引用及共享数据集，完成材料不会被业务后续修改覆盖。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_task_entry_binding", schema = "public")
public class TaskWorkEntryDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;

    private String taskId;
    private String entryKey;
    private String datasetId;
    private String configJson;
    private String businessJson;
    private Boolean inherited;
    private String submittedJson;
}
