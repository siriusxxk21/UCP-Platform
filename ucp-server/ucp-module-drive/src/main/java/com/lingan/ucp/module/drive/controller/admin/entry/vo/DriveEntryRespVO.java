package com.lingan.ucp.module.drive.controller.admin.entry.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 网盘节点 Response VO")
@Data
public class DriveEntryRespVO {

    @Schema(description = "节点编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long id;

    @Schema(description = "空间编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long spaceId;

    @Schema(
            description = "父节点编号，0 表示空间根目录",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "0")
    private Long parentId;

    @Schema(description = "节点名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "需求文档.pdf")
    private String name;

    @Schema(
            description = "节点类型：FOLDER 目录、FILE 文件",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "FILE")
    private String type;

    @Schema(description = "文件内容编号，目录为空", example = "2048")
    private Long fileId;

    @Schema(
            description = "文件大小（字节），目录为 0",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1024")
    private Long size;

    @Schema(description = "内容 MIME 类型", example = "application/pdf")
    private String mimeType;

    @Schema(description = "是否继承上级授权", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    private Boolean inheritParent;

    @Schema(
            description = "回收站状态：NORMAL 正常、TRASHED 已移入回收站",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "NORMAL")
    private String trashState;

    @Schema(description = "移入回收站的时间")
    private LocalDateTime trashedAt;

    @Schema(description = "移入回收站的操作人", example = "1024")
    private String trashedBy;

    @Schema(description = "当前用户是否已收藏", example = "false")
    private Boolean favorite;

    @Schema(description = "当前用户在该节点上的有效角色", example = "MANAGER")
    private String role;

    @Schema(description = "上传人编号", example = "1024")
    private String creator;

    @Schema(description = "上传时间")
    private LocalDateTime createTime;

    @Schema(description = "最近修改时间")
    private LocalDateTime updateTime;
}
