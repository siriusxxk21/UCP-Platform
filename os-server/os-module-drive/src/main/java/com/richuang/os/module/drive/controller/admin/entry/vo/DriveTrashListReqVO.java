package com.richuang.os.module.drive.controller.admin.entry.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

import lombok.Data;

@Schema(description = "管理后台 - 网盘回收站列表 Request VO")
@Data
public class DriveTrashListReqVO {

    @Schema(description = "空间编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "空间编号不能为空")
    private Long spaceId;

    @Schema(description = "名称关键字", example = "需求")
    private String name;
}
