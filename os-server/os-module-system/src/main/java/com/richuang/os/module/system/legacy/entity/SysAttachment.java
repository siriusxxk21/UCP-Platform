package com.richuang.os.module.system.legacy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统附件表
 */
@Data
@TableName("sys_attachment")
public class SysAttachment {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 存储路径
     */
    private String filePath;

    /**
     * 文件大小(字节)
     */
    private Long fileSize;

    /**
     * 文件MIME类型
     */
    private String fileType;

    /**
     * 存储类型:1-本地 2-OSS 3-MinIO
     */
    private Integer storageType;

    /**
     * 业务类型:message/scaffold/template等
     */
    private String businessType;

    /**
     * 关联业务ID
     */
    private String businessId;

    /**
     * 租户ID
     */
    @TableField(fill = FieldFill.INSERT)
    private String tenantId;

    /**
     * 上传人ID
     */
    private String createBy;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private Integer deleted;
}
