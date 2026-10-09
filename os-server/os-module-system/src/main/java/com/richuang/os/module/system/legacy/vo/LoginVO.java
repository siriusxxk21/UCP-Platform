package com.richuang.os.module.system.legacy.vo;

import lombok.Data;

import java.util.List;

@Data
public class LoginVO {

    private String token;

    private UserInfoVO userInfo;

    private List<MenuVO> menus;

    /**
     * 租户信息
     */
    private TenantVO tenantInfo;

    /**
     * 组织树
     */
    private List<OrganizationTreeVO> orgTree;

    /**
     * 部门树
     */
    private List<DepartmentTreeVO> deptTree;

    /**
     * 用户类型: 1-普通用户 2-租户管理员 3-系统管理员
     */
    private Integer userType;

    /**
     * 数据权限范围: 1-全部 2-本部门 3-本部门及以下 4-自定义
     */
    private Integer dataScope;

    /**
     * 自定义部门ID列表
     */
    private String customDeptIds;
}
