package com.richuang.os.module.infra.framework.file.core.client;

import cn.hutool.core.io.IoUtil;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * 文件客户端
 *
 * @author os
 */
public interface FileClient {

    /**
     * 获得客户端编号
     *
     * @return 客户端编号
     */
    Long getId();

    /**
     * 上传文件
     *
     * @param content 文件流
     * @param path    相对路径
     * @return 完整路径，即 HTTP 访问地址
     * @throws Exception 上传文件时，抛出 Exception 异常
     */
    String upload(byte[] content, String path, String type) throws Exception;

    /**
     * 流式上传文件，避免大文件整体载入内存
     *
     * 默认回退为整份读取，本地与 S3 存储器已覆写为真正的流式写入；
     * 返回路径语义与 {@link #upload(byte[], String, String)} 一致。
     *
     * @param content       文件流，由调用方负责关闭
     * @param contentLength 文件长度，部分存储器要求显式给出
     * @param path          相对路径
     * @param type          文件的 MIME 类型
     * @return 完整路径，即 HTTP 访问地址
     * @throws Exception 上传文件时，抛出 Exception 异常
     */
    default String upload(InputStream content, long contentLength, String path, String type) throws Exception {
        return upload(IoUtil.readBytes(content), path, type);
    }

    /**
     * 删除文件
     *
     * @param path 相对路径
     * @throws Exception 删除文件时，抛出 Exception 异常
     */
    void delete(String path) throws Exception;

    /**
     * 获得文件的内容
     *
     * @param path 相对路径
     * @return 文件的内容
     */
    byte[] getContent(String path) throws Exception;

    /**
     * 获得文件内容的长度
     *
     * @param path 相对路径
     * @return 文件长度；文件不存在时返回 null
     */
    default Long getContentLength(String path) throws Exception {
        byte[] content = getContent(path);
        return content == null ? null : (long) content.length;
    }

    /**
     * 流式获得文件内容，从指定偏移量开始读取，避免大文件整体载入内存
     *
     * @param path   相对路径
     * @param offset 起始偏移量，0 表示从头读取
     * @return 文件内容流；文件不存在时返回 null，调用方负责关闭
     */
    default InputStream getContentStream(String path, long offset) throws Exception {
        byte[] content = getContent(path);
        if (content == null) {
            return null;
        }
        return new ByteArrayInputStream(content,
                (int) Math.min(offset, content.length),
                (int) (content.length - Math.min(offset, content.length)));
    }

    // ========== 文件签名，目前仅 S3 支持 ==========

    /**
     * 获得文件预签名地址，用于上传
     *
     * @param path 相对路径
     * @return 文件预签名地址
     */
    default String presignPutUrl(String path) {
        throw new UnsupportedOperationException("不支持的操作");
    }

    /**
     * 生成文件预签名地址，用于读取
     *
     * @param url 完整的文件访问地址
     * @param expirationSeconds 访问有效期，单位秒
     * @return 文件预签名地址
     */
    default String presignGetUrl(String url, Integer expirationSeconds) {
        throw new UnsupportedOperationException("不支持的操作");
    }

}
