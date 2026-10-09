package com.lingan.ucp.nocode.work.dal.dataobject.event;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 事务性事件记录，当前只登记，不在业务事务中投递外部消息。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_work_event", schema = "public")
public class WorkEventDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;

    private String eventType;
    private String sourceType;
    private String sourceId;
    private String submissionId;
}
