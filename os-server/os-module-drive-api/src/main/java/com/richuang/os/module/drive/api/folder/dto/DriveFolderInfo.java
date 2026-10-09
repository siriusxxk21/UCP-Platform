package com.richuang.os.module.drive.api.folder.dto;

/**
 * 节点状态（不鉴权的查询结果，只给状态，不给内容）
 *
 * @param id 节点编号
 * @param spaceId 所在空间编号
 * @param spaceType 空间类型：PERSONAL / TEAM / BIZ
 * @param spaceName 空间名称
 * @param spaceEnabled 空间是否开启
 * @param parentId 父节点编号，0 表示空间根
 * @param name 节点名称
 * @param folder 是否目录
 * @param managed 是否业务受管节点（附件归档区）
 * @param trashed 是否在回收站
 */
public record DriveFolderInfo(
        Long id,
        Long spaceId,
        String spaceType,
        String spaceName,
        boolean spaceEnabled,
        Long parentId,
        String name,
        boolean folder,
        boolean managed,
        boolean trashed) {}
