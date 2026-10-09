package com.richuang.os.module.system.api.organization.dto;

import com.richuang.os.module.system.enums.organization.OrganizationStatusEnum;
import lombok.Data;

/**
 * 组织 Response DTO
 */
@Data
public class OrganizationRespDTO {

    /**
     * 组织编号
     */
    private String id;
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
     * 父组织编号
     */
    private String parentId;
    /**
     * 父组织路径
     */
    private String parentIds;
    /**
     * 层级
     */
    private Integer level;
    /**
     * 负责人编号
     */
    private String leaderId;
    /**
     * 状态
     *
     * 枚举 {@link OrganizationStatusEnum}：1 启用，0 禁用
     */
    private Integer status;

}
