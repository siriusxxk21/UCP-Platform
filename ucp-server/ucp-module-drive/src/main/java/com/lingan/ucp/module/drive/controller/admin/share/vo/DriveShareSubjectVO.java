package com.lingan.ucp.module.drive.controller.admin.share.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

@Schema(description = "管理后台 - 网盘分享接收主体 VO")
@Data
public class DriveShareSubjectVO {

    @Schema(
            description = "主体类型：USER 用户、DEPT 部门",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "USER")
    @NotEmpty(message = "主体类型不能为空")
    private String subjectType;

    @Schema(
            description = "主体编号：用户编号或部门编号",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1024")
    @NotNull(message = "主体编号不能为空")
    private Long subjectId;

    @Schema(description = "主体名称", example = "张三")
    private String subjectName;
}
