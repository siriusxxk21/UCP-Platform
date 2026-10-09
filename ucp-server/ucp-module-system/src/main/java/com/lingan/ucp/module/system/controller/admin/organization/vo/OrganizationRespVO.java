package com.lingan.ucp.module.system.controller.admin.organization.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 组织 Response VO")
@Data
public class OrganizationRespVO {

    @Schema(description = "组织编号", example = "1800000000000000001")
    private String id;

    @Schema(description = "组织编码", example = "HQ")
    private String orgCode;

    @Schema(description = "组织名称", example = "总部")
    private String orgName;

    @Schema(description = "组织类型: 1-公司 2-分公司 3-部门", example = "1")
    private Integer orgType;

    @Schema(description = "组织类型名称", example = "公司")
    private String orgTypeName;

    @Schema(description = "父组织编号", example = "0")
    private String parentId;

    @Schema(description = "父组织路径", example = "0,1")
    private String parentIds;

    @Schema(description = "层级", example = "1")
    private Integer level;

    @Schema(description = "负责人编号", example = "1024")
    private String leaderId;

    @Schema(description = "联系电话", example = "15601691000")
    private String phone;

    @Schema(description = "邮箱", example = "os@example.com")
    private String email;

    @Schema(description = "地址", example = "上海市")
    private String address;

    @Schema(description = "排序", example = "1")
    private Integer sortOrder;

    @Schema(description = "组织状态：1 启用，0 禁用", example = "1")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
