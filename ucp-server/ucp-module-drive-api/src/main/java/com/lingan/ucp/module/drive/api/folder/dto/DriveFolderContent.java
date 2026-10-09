package com.lingan.ucp.module.drive.api.folder.dto;

/**
 * 限定子树内文件节点的内容读取信息
 *
 * @param entryId 文件节点编号
 * @param name 展示名称
 * @param mimeType 节点上存的内容 MIME 类型
 * @param size 节点记录的文件大小（字节）
 * @param length 存储侧确认的内容长度；无法确认时为 null
 */
public record DriveFolderContent(
        Long entryId, String name, String mimeType, long size, Long length) {}
