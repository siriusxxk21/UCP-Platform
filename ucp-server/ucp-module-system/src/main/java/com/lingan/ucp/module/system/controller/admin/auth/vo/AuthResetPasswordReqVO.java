package com.lingan.ucp.module.system.controller.admin.auth.vo;

import com.lingan.ucp.framework.common.validation.Mobile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "管理后台 - 短信重置账号密码 Request VO")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResetPasswordReqVO {

    @Schema(description = "密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "Init@123456")
    @NotEmpty(message = "密码不能为空")
    @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d)\\S{8,32}$", message = "密码长度为 8-32 位，且必须包含字母和数字")
    private String password;

    @Schema(description = "手机号", requiredMode = Schema.RequiredMode.REQUIRED, example = "13312341234")
    @NotEmpty(message = "手机号不能为空")
    @Mobile
    private String mobile;

    @Schema(description = "手机短信验证码", requiredMode = Schema.RequiredMode.REQUIRED, example = "123456")
    @NotEmpty(message = "手机手机短信验证码不能为空")
    private String code;
}