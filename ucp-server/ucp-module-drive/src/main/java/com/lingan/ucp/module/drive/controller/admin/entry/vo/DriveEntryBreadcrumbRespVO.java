package com.lingan.ucp.module.drive.controller.admin.entry.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Schema(description = "管理后台 - 网盘路径节点 Response VO")
@Data
public class DriveEntryBreadcrumbRespVO {

    @Schema(
            description = "节点编号，0 表示空间根目录",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "0")
    private Long id;

    @Schema(description = "节点名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "需求资料")
    private String name;
}
