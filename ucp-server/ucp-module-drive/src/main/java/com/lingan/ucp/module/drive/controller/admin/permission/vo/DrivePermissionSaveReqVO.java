package com.lingan.ucp.module.drive.controller.admin.permission.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

@Schema(description = "管理后台 - 网盘授权新增/修改 Request VO")
@Data
public class DrivePermissionSaveReqVO {

    @Schema(description = "空间编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "空间编号不能为空")
    private Long spaceId;

    @Schema(
            description = "被授权的节点编号，0 表示整个空间",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "0")
    @NotNull(message = "被授权的节点编号不能为空")
    private Long entryId;

    @Schema(
            description = "授权主体类型：USER 用户、DEPT 部门",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "USER")
    @NotEmpty(message = "授权主体类型不能为空")
    private String subjectType;

    @Schema(description = "授权主体编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "授权主体编号不能为空")
    private Long subjectId;

    @Schema(
            description = "授权角色：VIEWER 可查看、EDITOR 可编辑、MANAGER 可管理",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "VIEWER")
    @NotEmpty(message = "授权角色不能为空")
    private String role;

    @Schema(description = "部门授权是否含下级部门，不传按含下级部门处理", example = "true")
    private Boolean includeChildren;
}
