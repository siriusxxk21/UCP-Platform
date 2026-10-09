package com.lingan.ucp.module.system.legacy.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 组织实体类
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_organization")
public class SysOrganization extends TenantBaseEntity {

    /**
     * 组织编码
     */
    private String orgCode;

    /**
     * 组织名称
     */
    private String orgName;

    /**
     * 组织类型: 1-公司 2-分公司 3-部门
     */
    private Integer orgType;

    /**
     * 父组织ID
     */
    private String parentId;

    /**
     * 父组织ID路径(逗号分隔)
     */
    private String parentIds;

    /**
     * 层级
     */
    private Integer level;

    /**
     * 负责人ID
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
     * 地址
     */
    private String address;

    /**
     * 排序
     */
    private Integer sortOrder;

    /**
     * 状态: 0-禁用 1-启用
     */
    private Integer status;
}
