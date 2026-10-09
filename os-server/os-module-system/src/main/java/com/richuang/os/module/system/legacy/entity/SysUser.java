package com.richuang.os.module.system.legacy.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends TenantBaseEntity {

    /**
     * 组织ID
     */
    private String orgId;

    /**
     * 部门ID
     */
    private String deptId;

    private String username;

    private String password;

    private String nickname;

    private String avatar;

    private String email;

    private String phone;

    private Integer status;

    /**
     * 用户类型: 1-普通用户 2-租户管理员 3-系统管理员
     */
    private Integer userType;

    /**
     * 数据权限范围: 1-全部 2-本部门 3-本部门及以下 4-自定义
     */
    private Integer dataScope;

    /**
     * 岗位
     */
    private String post;
}
