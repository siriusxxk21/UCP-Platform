package com.lingan.ucp.module.system.dal.dataobject.dept;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户部门关联实体。
 * 兼职部门仅记录关联关系，主部门同时同步到用户表供权限体系使用。
 */
@TableName("system_user_dept")
@Data
@EqualsAndHashCode(callSuper = true)
public class UserDeptDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long userId;
    private Long deptId;
    private String post;
    private Integer isMain;
}
