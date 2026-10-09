package com.lingan.ucp.module.system.legacy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员重置密码 DTO
 */
@Data
public class ResetPasswordDTO {

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, message = "密码长度不能少于6位")
    private String newPassword;
}
