package com.lingan.ucp.module.drive.api.bizfile.dto;

/**
 * 受管文件节点内容读取信息（业务附件流式读取原语返回值）
 *
 * <p>只承载内容响应所需的身份与元信息；授权过滤由无代码侧在调用前按业务绑定完成。 名称与 MIME 类型以受管节点记录为准；length 为存储侧确认的内容长度，无法确认时为 null，
 * 调用方按 size 降级处理 Range 与下载长度。
 *
 * @param entryId 受管文件节点编号（drive_entry.id）
 * @param spaceId 所属业务空间编号
 * @param fileId 文件内容编号（infra_file.id）
 * @param name 展示名称
 * @param size 节点记录的文件大小（字节）
 * @param mimeType 内容 MIME 类型
 * @param length 存储侧确认的内容长度；无法确认时为 null
 */
public record DriveBizFileContent(
        Long entryId,
        Long spaceId,
        Long fileId,
        String name,
        long size,
        String mimeType,
        Long length) {}
