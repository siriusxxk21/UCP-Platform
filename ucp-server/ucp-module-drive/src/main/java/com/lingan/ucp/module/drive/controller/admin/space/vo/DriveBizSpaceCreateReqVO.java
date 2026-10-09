package com.lingan.ucp.module.drive.controller.admin.space.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.Data;

@Schema(description = "管理后台 - 网盘业务空间新增 Request VO")
@Data
public class DriveBizSpaceCreateReqVO {

    @Schema(description = "空间名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "业务档案")
    @NotBlank(message = "空间名称不能为空")
    @Size(max = 64, message = "业务空间名称长度不能超过64个字符")
    private String name;

    @Schema(description = "容量配额（字节），0 表示不限制", example = "0")
    @PositiveOrZero(message = "容量配额不能小于0")
    private Long quotaBytes;
}
