package com.richuang.os.module.infra.framework.file.core.utils;

import cn.hutool.core.util.StrUtil;

import jakarta.servlet.http.HttpServletResponse;

import lombok.AllArgsConstructor;
import lombok.Getter;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * HTTP Range 支持工具：解析单区间请求，并按区间输出文件内容。
 *
 * <p>仅服务受保护资源的流式读取（音视频拖动、PDF 分片、断点续传）；公开文件接口保持整文件响应，不改变既有行为。
 *
 * @author os
 */
public class FileRangeUtils {

    /** 流式拷贝的缓冲区大小 */
    private static final int STREAM_BUFFER_SIZE = 8 * 1024;

    /** Range 请求头的单位前缀 */
    private static final String BYTES_PREFIX = "bytes=";

    private FileRangeUtils() {}

    /**
     * 单区间解析结果
     *
     * @param start 起始字节位置（从 0 开始）
     * @param end 结束字节位置（包含）
     * @param satisfiable false 表示区间越界，调用方应返回 416
     */
    @Getter
    @AllArgsConstructor
    public static final class ByteRange {

        private final long start;
        private final long end;
        private final boolean satisfiable;

        public long length() {
            return end - start + 1;
        }

        public static ByteRange unsatisfiable() {
            return new ByteRange(0, -1, false);
        }
    }

    /**
     * 解析单区间 Range 请求头，支持 {@code bytes=0-1023}、{@code bytes=1024-}、{@code bytes=-1024} 三种写法。
     *
     * @param rangeHeader 请求头值
     * @param contentLength 文件总长度
     * @return 解析结果；返回 null 表示没有可用区间（未携带该头、格式非法或多区间），调用方按整文件响应
     */
    public static ByteRange parseRangeHeader(String rangeHeader, long contentLength) {
        if (StrUtil.isEmpty(rangeHeader)
                || !StrUtil.startWithIgnoreCase(rangeHeader, BYTES_PREFIX)
                || contentLength <= 0) {
            return null;
        }
        String spec = StrUtil.removePrefixIgnoreCase(rangeHeader, BYTES_PREFIX).trim();
        if (StrUtil.contains(spec, StrUtil.COMMA)) {
            // 多区间不做响应合并，回退整文件，避免自行拼接 multipart/byteranges
            return null;
        }
        int dashIndex = spec.indexOf('-');
        if (dashIndex < 0) {
            return null;
        }
        String startPart = spec.substring(0, dashIndex).trim();
        String endPart = spec.substring(dashIndex + 1).trim();
        long start;
        long end;
        try {
            if (StrUtil.isEmpty(startPart)) {
                // bytes=-N：取末尾 N 字节
                long suffixLength = Long.parseLong(endPart);
                if (suffixLength <= 0) {
                    return ByteRange.unsatisfiable();
                }
                start = Math.max(0, contentLength - suffixLength);
                end = contentLength - 1;
            } else {
                start = Long.parseLong(startPart);
                end =
                        StrUtil.isEmpty(endPart)
                                ? contentLength - 1
                                : Math.min(Long.parseLong(endPart), contentLength - 1);
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        if (start < 0 || start >= contentLength || end < start) {
            return ByteRange.unsatisfiable();
        }
        return new ByteRange(start, end, true);
    }

    /**
     * 输出文件内容：按 range 返回 200 整文件或 206 部分内容，区间越界时返回 416。
     *
     * <p>调用前需已根据 range 把内容流定位到 start；本方法负责 Content-Length、Content-Range、Accept-Ranges
     * 与响应体，并在结束时关闭内容流。
     *
     * @param response 响应
     * @param contentLength 文件总长度
     * @param range 区间，null 表示整文件
     * @param contentStream 内容流，调用方保证已定位到 range.start
     */
    public static void writeStream(
            HttpServletResponse response,
            long contentLength,
            ByteRange range,
            InputStream contentStream)
            throws IOException {
        try {
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            if (range != null && !range.isSatisfiable()) {
                response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
                response.setHeader(
                        HttpHeaders.CONTENT_RANGE, StrUtil.format("bytes */{}", contentLength));
                return;
            }
            if (contentStream == null) {
                response.setStatus(HttpStatus.NOT_FOUND.value());
                return;
            }
            long length = contentLength;
            if (range != null) {
                response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
                response.setHeader(
                        HttpHeaders.CONTENT_RANGE,
                        StrUtil.format(
                                "bytes {}-{}/{}", range.getStart(), range.getEnd(), contentLength));
                length = range.length();
            }
            response.setContentLengthLong(length);
            copy(contentStream, response.getOutputStream(), length);
        } finally {
            if (contentStream != null) {
                contentStream.close();
            }
        }
    }

    /** 按长度拷贝内容流，避免一次性读入内存 */
    private static void copy(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
        out.flush();
    }
}
