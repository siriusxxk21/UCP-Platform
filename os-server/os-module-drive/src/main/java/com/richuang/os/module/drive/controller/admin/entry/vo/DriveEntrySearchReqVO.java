package com.richuang.os.module.drive.controller.admin.entry.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

@Schema(description = "管理后台 - 网盘节点搜索 Request VO")
@Data
public class DriveEntrySearchReqVO {

    @Schema(description = "空间编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "空间编号不能为空")
    private Long spaceId;

    @Schema(description = "名称关键字", requiredMode = Schema.RequiredMode.REQUIRED, example = "需求")
    @NotEmpty(message = "名称关键字不能为空")
    private String name;

    @Schema(description = "返回条数上限，最大 200", example = "50")
    private Integer limit;
}
