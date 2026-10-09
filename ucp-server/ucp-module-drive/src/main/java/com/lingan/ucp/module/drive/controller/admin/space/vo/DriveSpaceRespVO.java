package com.lingan.ucp.module.drive.controller.admin.space.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 网盘空间 Response VO")
@Data
public class DriveSpaceRespVO {

    @Schema(description = "空间编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "空间名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "产品部空间")
    private String name;

    @Schema(
            description = "空间类型：PERSONAL 个人空间、TEAM 团队空间、BIZ 业务空间",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "TEAM")
    private String type;

    @Schema(description = "归属主体用户编号，业务空间为空", example = "1024")
    private Long ownerId;

    @Schema(description = "归属主体用户名称", example = "张三")
    private String ownerName;

    @Schema(description = "归属部门编号", example = "100")
    private Long ownerDeptId;

    @Schema(description = "归属部门名称", example = "产品部")
    private String ownerDeptName;

    @Schema(
            description = "容量配额（字节），0 表示不限制",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "0")
    private Long quotaBytes;

    @Schema(
            description = "已用容量（字节），不含回收站内容",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "0")
    private Long usedBytes;

    @Schema(
            description = "空间状态：0 开启、1 关闭",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "0")
    private Integer status;

    @Schema(
            description = "当前用户在该空间的有效角色：VIEWER 可查看、EDITOR 可编辑、MANAGER 可管理；业务空间为空",
            example = "MANAGER")
    private String role;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;
}
