package com.lingan.ucp.module.drive.controller.admin.entry.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

import lombok.Data;

@Schema(description = "管理后台 - 网盘节点授权继承设置 Request VO")
@Data
public class DriveEntryInheritReqVO {

    @Schema(description = "节点编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "节点编号不能为空")
    private Long id;

    @Schema(
            description = "是否继承上级授权：false 时只按本级及以下授权判定",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "false")
    @NotNull(message = "继承设置不能为空")
    private Boolean inheritParent;
}
