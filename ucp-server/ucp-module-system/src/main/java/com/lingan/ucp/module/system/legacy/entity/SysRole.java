package com.lingan.ucp.module.system.legacy.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.common.entity.TenantBaseEntity;
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
