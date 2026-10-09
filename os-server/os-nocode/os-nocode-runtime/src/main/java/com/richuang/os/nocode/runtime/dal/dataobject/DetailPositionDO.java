package com.richuang.os.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.*;

/** 独立于业务物理表的内部明细排序，兼容保留结构的纳管表。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_detail_position", schema = "public")
public class DetailPositionDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long objectId;
    private Long detailId;
    private String parentId;
    private String recordId;
    private Integer position;
}
