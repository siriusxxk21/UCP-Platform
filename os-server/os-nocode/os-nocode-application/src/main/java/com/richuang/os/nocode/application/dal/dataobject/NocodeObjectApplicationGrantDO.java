package com.richuang.os.nocode.application.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 全局对象授予应用的上限，独立于对象版本和应用发布版本。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nocode_object_application_grant")
public class NocodeObjectApplicationGrantDO extends BaseDO {
    @TableId private Long id;
    private Long objectId;
    private Long applicationId;
    private Integer lockVersion;
    private String grantJson;
    private String reason;
}
