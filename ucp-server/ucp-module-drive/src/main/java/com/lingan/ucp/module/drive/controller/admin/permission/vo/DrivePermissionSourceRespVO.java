package com.lingan.ucp.module.drive.controller.admin.permission.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Schema(description = "管理后台 - 网盘有效权限来源 Response VO")
@Data
public class DrivePermissionSourceRespVO {

    @Schema(description = "授权编号", example = "1024")
    private Long id;

    @Schema(
            description = "授权主体类型：USER 用户、DEPT 部门",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "DEPT")
    private String subjectType;

    @Schema(description = "授权主体编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "100")
    private Long subjectId;

    @Schema(description = "授权主体名称", example = "产品部")
    private String subjectName;

    @Schema(description = "授权角色", requiredMode = Schema.RequiredMode.REQUIRED, example = "EDITOR")
    private String role;

    @Schema(description = "部门授权是否含下级部门", example = "true")
    private Boolean includeChildren;

    @Schema(
            description = "授权来源节点编号，0 表示空间级授权",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "0")
    private Long sourceEntryId;

    @Schema(description = "授权来源节点名称", example = "产品资料")
    private String sourceEntryName;

    @Schema(description = "是否为空间归属主体自动获得的权限", example = "false")
    private Boolean ownerGrant;
}
