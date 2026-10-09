package com.lingan.ucp.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 追加式任务事件及完成材料；历史内容不随模板或业务记录更新改写。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_task_event", schema = "public")
public class TaskHistoryDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;

    private String taskId;
    private String rootId;
    private String eventType;
    private String note;
    private String materialJson;
    private String requestKey;
    private String requestHash;
}
