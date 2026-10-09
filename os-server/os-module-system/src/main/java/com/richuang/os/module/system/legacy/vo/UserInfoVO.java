package com.richuang.os.module.system.legacy.vo;

import lombok.Data;

@Data
public class UserInfoVO {

    private String id;

    private String username;

    private String nickname;

    private String avatar;

    /**
     * 租户ID
     */
    private String tenantId;

    /**
     * 组织ID
     */
    private String orgId;

    /**
     * 部门ID
     */
    private String deptId;

    /**
     * 部门名称
     */
    private String deptName;

    /**
     * 岗位
     */
    private String post;

    /**
     * 数据权限范围: 1-全部 2-本部门 3-本部门及以下 4-自定义
     */
    private Integer dataScope;

    /**
     * 用户类型: 1-普通用户 2-租户管理员 3-系统管理员
     */
    private Integer userType;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 手机号
     */
    private String phone;
}
