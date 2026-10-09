package com.lingan.ucp.module.system.legacy.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 租户实体类
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_tenant")
public class SysTenant extends BaseEntity {

    /**
     * 租户编码
     */
    private String tenantCode;

    /**
     * 租户名称
     */
    private String tenantName;

    /**
     * 租户类型: 1-企业 2-个人 3-试用
     */
    private Integer tenantType;

    /**
     * 联系人
     */
    private String contactName;

    /**
     * 联系电话
     */
    private String contactPhone;

    /**
     * 联系邮箱
     */
    private String contactEmail;

    /**
     * 租户Logo
     */
    private String logoUrl;

    /**
     * 独立域名
     */
    private String domain;

    /**
     * 隔离策略: row/schema/database
     */
    private String isolationStrategy;

    /**
     * 状态: 0-禁用 1-启用 2-过期
     */
    private Integer status;

    /**
     * 过期时间
     */
    private LocalDateTime expireTime;

    /**
     * 最大用户数
     */
    private Integer maxUserCount;

    /**
     * 最大存储空间(字节)
     */
    private Long maxStorageSize;
}
