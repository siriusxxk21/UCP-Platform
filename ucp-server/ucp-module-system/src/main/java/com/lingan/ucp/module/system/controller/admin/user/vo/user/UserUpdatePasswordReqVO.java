package com.lingan.ucp.module.system.controller.admin.user.vo.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "管理后台 - 用户更新密码 Request VO")
@Data
public class UserUpdatePasswordReqVO {

    @Schema(description = "用户 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "用户 ID 不能为空")
    private Long id;

    @Schema(description = "密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "Init@123456")
    @NotEmpty(message = "密码不能为空")
    @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d)\\S{8,32}$", message = "密码长度为 8-32 位，且必须包含字母和数字")
    private String password;

}
