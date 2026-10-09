package com.lingan.ucp.module.system.legacy.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 租户编码（可选，不传则使用用户默认租户）
     */
    private String tenantCode;
}
