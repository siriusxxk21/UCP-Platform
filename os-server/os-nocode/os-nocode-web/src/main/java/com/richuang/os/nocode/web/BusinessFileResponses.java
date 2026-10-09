package com.richuang.os.nocode.web;

import cn.hutool.core.util.StrUtil;

import com.richuang.os.module.infra.framework.file.core.utils.FileRangeUtils;
import com.richuang.os.module.infra.framework.file.core.utils.FileTypeUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.LongFunction;

/** 已完成授权核验的业务附件统一流输出；任务入口与普通入口不重复维护响应安全策略。 */
@Slf4j
public final class BusinessFileResponses {
    private BusinessFileResponses() {}

    public static void stream(
            HttpServletRequest request,
            HttpServletResponse response,
            String name,
            String mimeType,
            Long length,
            long size,
            boolean inline,
            LongFunction<InputStream> open)
            throws IOException {
        long total = length != null && length > 0 ? length : size;
        // 历史附件的 mimeType 可能来自客户端；响应类型按服务端文件名检测重算。
        String detected = FileTypeUtils.getMineType(name);
        String type = StrUtil.blankToDefault(detected, "application/octet-stream");
        boolean safeInline = inline && FileTypeUtils.isPreviewable(type);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        if (safeInline) {
            response.setHeader(
                    "Content-Security-Policy",
                    "default-src 'none'; sandbox; img-src 'self' data:; media-src 'self'; style-src"
                            + " 'unsafe-inline'");
        }
        response.setContentType(type);
        FileTypeUtils.writeContentDisposition(response, name, type, safeInline);
        FileRangeUtils.ByteRange range =
                FileRangeUtils.parseRangeHeader(request.getHeader(HttpHeaders.RANGE), total);
        if (range != null && !range.isSatisfiable()) {
            // 越界区间不打开内容流，直接按 RFC 7233 返回 416
            response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE, StrUtil.format("bytes */{}", total));
            return;
        }
        long start = range != null ? range.getStart() : 0L;
        try (InputStream stream = open.apply(start)) {
            // writeStream 负责 200/206、Content-Range 与响应体，并保证内容流关闭
            FileRangeUtils.writeStream(response, total, range, stream);
        } catch (IOException interrupted) {
            // 预览时的连接中断属于常态，降级为调试日志，避免污染错误日志
            log.debug("[content][业务文件({}) 内容传输中断]", name, interrupted);
        }
    }
}
