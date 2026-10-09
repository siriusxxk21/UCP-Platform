package com.richuang.os.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 不同应用写同一全局对象字段时共享计数器；计数不随应用版本回退。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nocode_business_counter")
public class NocodeBusinessCounterDO extends BaseDO {
    @TableId private Long id;
    private Long objectId;
    private Long fieldId;
    private String periodKey;
    private Long nextValue;
}
