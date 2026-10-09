package com.richuang.os.module.system.legacy.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends TenantBaseEntity {

    private String roleName;

    private String roleCode;

    private String description;

    private Integer status;
}
