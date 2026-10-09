package com.richuang.os.module.drive.controller.admin.permission.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 网盘授权 Response VO")
@Data
public class DrivePermissionRespVO {

    @Schema(description = "授权编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long id;

    @Schema(description = "空间编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long spaceId;

    @Schema(
            description = "被授权的节点编号，0 表示整个空间",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "0")
    private Long entryId;

    @Schema(description = "被授权的节点名称", example = "产品资料")
    private String entryName;

    @Schema(
            description = "授权主体类型：USER 用户、DEPT 部门",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "USER")
    private String subjectType;

    @Schema(description = "授权主体编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long subjectId;

    @Schema(description = "授权主体名称", example = "张三")
    private String subjectName;

    @Schema(
            description = "授权角色：VIEWER 可查看、EDITOR 可编辑、MANAGER 可管理",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "VIEWER")
    private String role;

    @Schema(
            description = "部门授权是否含下级部门",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "true")
    private Boolean includeChildren;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;
}
