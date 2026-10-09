package com.lingan.ucp.module.drive.controller.admin.entry.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

import lombok.Data;

@Schema(description = "管理后台 - 网盘节点移动 Request VO")
@Data
public class DriveEntryMoveReqVO {

    @Schema(description = "节点编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "节点编号不能为空")
    private Long id;

    @Schema(
            description = "目标父节点编号，0 表示空间根目录",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "0")
    @NotNull(message = "目标父节点编号不能为空")
    private Long targetParentId;
}
