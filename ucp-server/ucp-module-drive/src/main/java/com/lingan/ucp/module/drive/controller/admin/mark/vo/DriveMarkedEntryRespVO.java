package com.lingan.ucp.module.drive.controller.admin.mark.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 网盘收藏与最近使用 Response VO")
@Data
public class DriveMarkedEntryRespVO {

    @Schema(description = "节点编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long entryId;

    @Schema(description = "空间编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long spaceId;

    @Schema(description = "空间名称", example = "产品部空间")
    private String spaceName;

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

    @Schema(description = "文件大小（字节）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long size;

    @Schema(description = "内容 MIME 类型", example = "application/pdf")
    private String mimeType;

    @Schema(description = "是否已收藏", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    private Boolean favorite;

    @Schema(description = "最近访问时间")
    private LocalDateTime accessTime;
}
