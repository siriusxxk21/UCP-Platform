package com.lingan.ucp.module.system.legacy.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量添加用户到部门DTO
 */
@Data
public class BatchAddUsersDTO {

    /**
     * 用户ID列表
     */
    @NotEmpty(message = "用户ID列表不能为空")
    private List<String> userIds;

    /**
     * 岗位
     */
    private String post;

    /**
     * 是否设为主部门
     */
    private Boolean isMain;
}
