package com.lingan.ucp.module.system.legacy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class RoleDTO {

    private String id;

    @NotBlank(message = "角色名称不能为空")
    private String roleName;

    @NotBlank(message = "角色编码不能为空")
    private String roleCode;

    private String description;

    @NotNull(message = "状态不能为空")
    private Integer status;

    private List<String> menuIds;
}
