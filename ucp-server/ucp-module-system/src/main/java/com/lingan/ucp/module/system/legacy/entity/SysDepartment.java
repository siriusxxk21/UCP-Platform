package com.lingan.ucp.module.system.legacy.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 部门实体类
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_department")
public class SysDepartment extends TenantBaseEntity {

    /**
     * 所属组织ID
     */
    private String orgId;

    /**
     * 部门编码
     */
    private String deptCode;

    /**
     * 部门名称
     */
    private String deptName;

    /**
     * 父部门ID
     */
    private String parentId;

    /**
     * 父部门ID路径(逗号分隔)
     */
    private String parentIds;

    /**
     * 层级
     */
    private Integer level;

    /**
     * 部门负责人ID
     */
    private String leaderId;

    /**
     * 联系电话
     */
    private String phone;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 排序
     */
    private Integer sortOrder;

    /**
     * 状态: 0-禁用 1-启用
     */
    private Integer status;
}
