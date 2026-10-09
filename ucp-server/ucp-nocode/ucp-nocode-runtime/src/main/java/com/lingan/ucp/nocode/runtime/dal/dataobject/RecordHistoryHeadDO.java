package com.lingan.ucp.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 每张业务表的历史覆盖起点，不能由查询起始日期反推。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nocode_record_history_head")
public class RecordHistoryHeadDO extends BaseDO {
    @TableId private Long objectId;
    private java.time.OffsetDateTime coveredFrom;
}
