package com.lingan.ucp.module.drive.controller.admin.share.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 网盘分享 Response VO")
@Data
public class DriveShareRespVO {

    @Schema(description = "分享编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long id;

    @Schema(description = "空间编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long spaceId;

    @Schema(description = "空间名称", example = "产品部空间")
    private String spaceName;

    @Schema(description = "被分享的节点编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "2048")
    private Long entryId;

    @Schema(description = "被分享的节点名称", example = "需求资料")
    private String entryName;

    @Schema(description = "被分享的节点类型：FOLDER 目录、FILE 文件", example = "FOLDER")
    private String entryType;

    @Schema(
            description = "分享授予的角色",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "VIEWER")
    private String role;

    @Schema(description = "失效时间，为空表示长期有效")
    private LocalDateTime expireTime;

    @Schema(
            description = "分享状态：ACTIVE 生效、REVOKED 已撤销",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "ACTIVE")
    private String status;

    @Schema(description = "分享创建人编号", example = "1024")
    private String creator;

    @Schema(description = "分享创建人名称", example = "张三")
    private String creatorName;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

    @Schema(description = "接收主体列表")
    private List<DriveShareSubjectVO> subjects;
}
