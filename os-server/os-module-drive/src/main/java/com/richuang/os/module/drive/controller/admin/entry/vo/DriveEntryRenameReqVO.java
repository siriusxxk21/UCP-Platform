package com.richuang.os.module.drive.controller.admin.entry.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

@Schema(description = "管理后台 - 网盘节点重命名 Request VO")
@Data
public class DriveEntryRenameReqVO {

    @Schema(description = "节点编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "节点编号不能为空")
    private Long id;

    @Schema(description = "新名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "需求资料-新版")
    @NotEmpty(message = "名称不能为空")
    private String name;
}
