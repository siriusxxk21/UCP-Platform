package com.richuang.os.module.drive.api.bizfile.dto;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 受管网盘节点信息（业务文件浏览/搜索原语返回值）
 *
 * <p>只承载展示与定位所需的字段；授权过滤由无代码侧按业务绑定完成。
 *
 * @param id 节点编号
 * @param parentId 父节点编号，0 表示空间根
 * @param spaceId 所属空间编号
 * @param name 展示名称
 * @param folder 是否目录
 * @param fileId 文件内容编号（infra_file.id）；目录为 null
 * @param size 文件大小（字节），目录为 0
 * @param mimeType 内容 MIME 类型；目录为 null
 * @param creator 创建者
 * @param createTime 创建时间
 */
@Builder
public record DriveBizEntryDTO(
        Long id,
        Long parentId,
        Long spaceId,
        String name,
        boolean folder,
        Long fileId,
        long size,
        String mimeType,
        String creator,
        LocalDateTime createTime) {}
