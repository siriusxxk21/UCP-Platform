package com.richuang.os.module.system.legacy.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户部门关联DTO
 */
@Data
public class UserDeptDTO {

    /**
     * 用户ID
     */
    @NotNull(message = "用户ID不能为空")
    private String userId;

    /**
     * 部门ID
     */
    @NotNull(message = "部门ID不能为空")
    private String deptId;

    /**
     * 岗位
     */
    private String post;

    /**
     * 是否设为主部门
     */
    private Boolean isMain;
}
