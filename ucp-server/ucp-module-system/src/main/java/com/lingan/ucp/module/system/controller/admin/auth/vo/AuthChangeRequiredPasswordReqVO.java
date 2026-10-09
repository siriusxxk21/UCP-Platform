package com.lingan.ucp.module.system.controller.admin.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 强制改密请求，只接受登录预认证阶段签发的一次性凭证。
 */
@Schema(description = "管理后台 - 强制修改密码 Request VO")
@Data
public class AuthChangeRequiredPasswordReqVO {

    @Schema(description = "一次性改密凭证", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "改密凭证不能为空")
    private String passwordChangeToken;

    @Schema(description = "新密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "Init@123456")
    @NotEmpty(message = "新密码不能为空")
    @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d)\\S{8,32}$",
            message = "密码长度为 8-32 位，且必须包含字母和数字")
    private String newPassword;

}
