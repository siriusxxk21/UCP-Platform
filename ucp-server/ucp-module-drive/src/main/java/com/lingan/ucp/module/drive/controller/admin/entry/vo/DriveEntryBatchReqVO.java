package com.lingan.ucp.module.drive.controller.admin.entry.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotEmpty;

import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 网盘节点批量操作 Request VO")
@Data
public class DriveEntryBatchReqVO {

    @Schema(
            description = "节点编号列表",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "[1024, 1025]")
    @NotEmpty(message = "节点编号列表不能为空")
    private List<Long> ids;
}
