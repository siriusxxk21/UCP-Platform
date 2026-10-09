package com.lingan.ucp.module.drive.api.bizfile.dto;

/**
 * 临时上传内容元信息
 *
 * <p>业务记录尚未保存时内容只存在于文件底座：没有网盘节点身份，读取依据是无代码侧的 上传会话归属判断。名称、大小与类型取自受保护文件记录，调用方不得另传。
 *
 * @param fileId 文件内容编号（infra_file.id）
 * @param name 原始文件名
 * @param size 文件大小（字节）
 * @param mimeType 内容类型
 * @param length 存储侧确认的内容长度，无法确认时为 null，调用方按 size 降级
 */
public record DriveBizTemporaryContent(
        Long fileId, String name, long size, String mimeType, Long length) {}
