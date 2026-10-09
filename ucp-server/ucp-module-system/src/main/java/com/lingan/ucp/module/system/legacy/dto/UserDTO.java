package com.lingan.ucp.module.system.legacy.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UserDTO {

    private String id;

    @NotBlank(message = "用户名不能为空")
    private String username;

    private String password;

    private String nickname;

    private String avatar;

    @Email(message = "邮箱格式不正确")
    private String email;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    private String orgId;

    private String deptId;

    private Integer userType;

    private Integer dataScope;

    private String post;

    @NotNull(message = "状态不能为空")
    private Integer status;
}
