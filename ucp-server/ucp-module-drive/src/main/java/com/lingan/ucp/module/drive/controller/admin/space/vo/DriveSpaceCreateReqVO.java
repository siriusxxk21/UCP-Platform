package com.lingan.ucp.module.drive.controller.admin.space.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotEmpty;

import lombok.Data;

@Schema(description = "管理后台 - 网盘团队空间新增 Request VO")
@Data
public class DriveSpaceCreateReqVO {

    @Schema(description = "空间名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "产品部空间")
    @NotEmpty(message = "空间名称不能为空")
    private String name;

    @Schema(description = "归属主体用户编号，不传默认为创建人", example = "1024")
    private Long ownerId;

    @Schema(description = "归属部门编号", example = "100")
    private Long ownerDeptId;

    @Schema(description = "容量配额（字节），0 表示不限制", example = "0")
    private Long quotaBytes;
}
