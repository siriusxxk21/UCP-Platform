package com.richuang.os.nocode.metadata.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 草稿主表写入参数，物理表名仅作为元数据保存。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_object_table", schema = "public")
public class NocodeObjectTableDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long objectVersionId;
    private Long stableTableId;
    private String tableName;
    private String tableCode;
    private String configJson;
}
