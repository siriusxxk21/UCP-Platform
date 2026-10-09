package com.richuang.os.module.infra.api.file;

import com.richuang.os.module.infra.dal.dataobject.file.FileDO;

import jakarta.validation.constraints.NotEmpty;

import java.io.InputStream;

/**
 * 文件 API 接口
 *
 * @author os
 */
public interface FileApi {

    /**
     * 保存文件，并返回文件的访问路径
     *
     * @param content 文件内容
     * @return 文件路径
     */
    default FileDO createFile(byte[] content) {
        return createFile(content, null, null, null);
    }

    /**
     * 保存文件，并返回文件的访问路径
     *
     * @param content 文件内容
     * @param name 文件名称，允许空
     * @return 文件路径
     */
    default FileDO createFile(byte[] content, String name) {
        return createFile(content, name, null, null);
    }

    /**
     * 保存文件，并返回文件的访问路径
     *
     * @param content 文件内容
     * @param name 文件名称，允许空
     * @param directory 目录，允许空
     * @param type 文件的 MIME 类型，允许空
     * @return 文件路径
     */
    FileDO createFile(
            @NotEmpty(message = "文件内容不能为空") byte[] content,
            String name,
            String directory,
            String type);

    /**
     * 生成文件预签名地址，用于读取
     *
     * @param url 完整的文件访问地址
     * @param expirationSeconds 访问有效期，单位秒
     * @return 文件预签名地址
     */
    String presignGetUrl(@NotEmpty(message = "URL 不能为空") String url, Integer expirationSeconds);

    /** 按文件 ID 读取内容，供跨模块暂存文件消费；调用方无需感知底层配置和路径。 */
    byte[] getFileContent(Long fileId);

    /** 按文件 ID 删除内容和元数据，重复清理不存在的文件按成功处理。 */
    void deleteFile(Long fileId);

    /**
     * 保存受保护文件：内容只能由业务模块鉴权后读取，通用文件接口拒绝访问。
     *
     * @param content 文件内容
     * @param name 文件名称，允许空
     * @param directory 目录，允许空
     * @param type 文件的 MIME 类型，允许空
     * @return 文件记录
     */
    FileDO createProtectedFile(byte[] content, String name, String directory, String type);

    /**
     * 流式保存受保护文件：内容按流写入存储器，不在内存中整份缓冲，用于大文件上传。
     *
     * @param content 文件内容流，由调用方负责关闭
     * @param contentLength 文件长度
     * @param name 文件名称，允许空
     * @param directory 目录，允许空
     * @param type 文件的 MIME 类型，允许空
     * @return 文件记录
     */
    FileDO createProtectedFile(
            InputStream content, long contentLength, String name, String directory, String type);

    /** 按指定文件配置流式保存受保护文件，供需要独立选择存储源的业务使用。 */
    FileDO createProtectedFile(
            Long configId,
            InputStream content,
            long contentLength,
            String name,
            String directory,
            String type);

    /**
     * 按文件 ID 流式读取内容，从指定偏移量开始，避免大文件整体载入内存。
     *
     * <p>调用方必须先完成业务鉴权；返回的流由调用方关闭。文件记录不存在时返回 null。
     *
     * @param fileId 文件编号
     * @param offset 起始偏移量，0 表示从头读取
     * @return 文件内容流
     */
    InputStream getFileContentStream(Long fileId, long offset);

    /**
     * 按文件 ID 获得内容长度，用于 Range 响应与下载进度。
     *
     * @param fileId 文件编号
     * @return 文件长度；文件记录不存在时返回 null
     */
    Long getFileContentLength(Long fileId);
}
