package com.lingan.ucp.module.drive.api.folder.dto;

import java.time.LocalDateTime;

/**
 * 限定子树内的一个节点
 *
 * @param id 节点编号
 * @param spaceId 所在空间编号
 * @param parentId 父节点编号；父节点是子树的根时为 0（根自身的编号不出现在任何返回值里）
 * @param name 名称
 * @param type FOLDER / FILE
 * @param size 文件大小（字节），目录为 0
 * @param mimeType 内容 MIME 类型
 * @param role 调用者在该节点上的有效角色；列表、搜索、回收站结果不填
 * @param creator 创建人
 * @param createTime 创建时间
 * @param updateTime 更新时间
 * @param trashedAt 移入回收站的时间
 * @param trashedBy 移入回收站的操作人
 * @param modifiable 这次调用能不能改名/移动/删除这个节点（仅供界面呈现；服务端每次操作仍重新判定）
 */
public record DriveFolderNode(
        Long id,
        Long spaceId,
        Long parentId,
        String name,
        String type,
        Long size,
        String mimeType,
        String role,
        String creator,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        LocalDateTime trashedAt,
        String trashedBy,
        boolean modifiable) {}
