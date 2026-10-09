package com.richuang.os.nocode.metadata.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 对象版本写入参数；使用标准 INSERT 主键回填，兼容底座 SQL 拦截器。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_object_version", schema = "public")
public class NocodeObjectVersionDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long objectId;
    private String schemaJson;
    private String schemaChecksum;
}
