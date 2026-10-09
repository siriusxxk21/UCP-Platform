package com.richuang.os.nocode.application.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 每个应用一份实时授权配置，继承底座审计和逻辑删除字段。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nocode_application_access")
public class NocodeApplicationAccessDO extends BaseDO {
    @TableId private Long id;
    private Long applicationId;
    private Integer lockVersion;
    private String policyJson;
}
