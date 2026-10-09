package com.richuang.os.module.system.legacy.dto;

import lombok.Data;

@Data
public class RoleQueryDTO {

    private String roleName;

    private String roleCode;

    private Integer status;

    private Integer pageNum = 1;

    private Integer pageSize = 10;
}
