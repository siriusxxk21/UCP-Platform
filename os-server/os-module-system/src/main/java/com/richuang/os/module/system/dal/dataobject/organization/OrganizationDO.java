package com.richuang.os.module.system.dal.dataobject.organization;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组织表
 */
@TableName("system_organization")
@Data
public class OrganizationDO extends TenantBaseDO {

    public static final Long PARENT_ID_ROOT = 0L;

    @TableId
    private Long id;

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
    private Long parentId;

    /**
     * 父组织ID路径，逗号分隔
     */
    private String parentIds;

    /**
     * 层级
     */
    private Integer level;

    /**
     * 负责人ID
     */
    private Long leaderId;

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
