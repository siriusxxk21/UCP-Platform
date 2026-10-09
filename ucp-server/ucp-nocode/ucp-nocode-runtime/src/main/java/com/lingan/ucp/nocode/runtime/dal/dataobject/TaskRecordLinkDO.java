package com.lingan.ucp.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 普通记录与既有任务的显式关联；不替换任务办理记录或项目归属。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_task_record_link", schema = "public")
public class TaskRecordLinkDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;

    private String taskId;
    private String applicationId;
    private String objectId;
    private String recordId;
    private String label;
}
