package com.lingan.ucp.module.system.legacy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 组织DTO
 */
@Data
public class OrganizationDTO {

    private String id;

    @NotBlank(message = "组织编码不能为空")
    private String orgCode;

    @NotBlank(message = "组织名称不能为空")
    private String orgName;

    @NotNull(message = "组织类型不能为空")
    private Integer orgType;

    private String parentId;

    private String leaderId;

    private String phone;

    private String email;

    private String address;

    private Integer sortOrder;

    private Integer status;
}
