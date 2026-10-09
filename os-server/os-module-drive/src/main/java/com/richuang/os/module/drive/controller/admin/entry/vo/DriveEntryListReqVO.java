package com.richuang.os.module.drive.controller.admin.entry.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

import lombok.Data;

@Schema(description = "管理后台 - 网盘节点列表 Request VO")
@Data
public class DriveEntryListReqVO {

    @Schema(description = "空间编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "空间编号不能为空")
    private Long spaceId;

    @Schema(
            description = "父节点编号，0 表示空间根目录",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "0")
    @NotNull(message = "父节点编号不能为空")
    private Long parentId;
}
