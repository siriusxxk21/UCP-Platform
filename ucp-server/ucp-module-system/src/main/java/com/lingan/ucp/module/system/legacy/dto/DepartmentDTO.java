package com.lingan.ucp.module.system.legacy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 部门DTO
 */
@Data
public class DepartmentDTO {

    private String id;

    @NotNull(message = "所属组织不能为空")
    private String orgId;

    @NotBlank(message = "部门编码不能为空")
    private String deptCode;

    @NotBlank(message = "部门名称不能为空")
    private String deptName;

    private String parentId;

    private String leaderId;

    private String phone;

    private String email;

    private Integer sortOrder;

    private Integer status;
}
