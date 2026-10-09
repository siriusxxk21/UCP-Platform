package com.lingan.ucp.module.infra.framework.file.core.utils;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.lingan.ucp.framework.common.util.http.HttpUtils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;

import java.io.IOException;

/**
 * 文件类型 Utils
 *
 * @author os
 */
@Slf4j
public class FileTypeUtils {

    private static final Tika TIKA = new Tika();

    /**
     * 获得文件的 mineType，对于 doc，jar 等文件会有误差
     *
     * @param data 文件内容
     * @return mineType 无法识别时会返回“application/octet-stream”
     */
    @SneakyThrows
    public static String getMineType(byte[] data) {
        return TIKA.detect(data);
    }

    /**
     * 已知文件名，获取文件类型，在某些情况下比通过字节数组准确，例如使用 jar 文件时，通过名字更为准确
     *
     * @param name 文件名
     * @return mineType 无法识别时会返回“application/octet-stream”
     */
    public static String getMineType(String name) {
        return TIKA.detect(name);
    }

    /**
     * 在拥有文件和数据的情况下，最好使用此方法，最为准确
     *
     * @param data 文件内容
     * @param name 文件名
     * @return mineType 无法识别时会返回“application/octet-stream”
     */
    public static String getMineType(byte[] data, String name) {
        return TIKA.detect(data, name);
    }

    /**
     * 根据 mineType 获得文件后缀
     *
     * 注意：如果获取不到，或者发生异常，都返回 null
     *
     * @param mineType 类型
     * @return 后缀，例如说 .pdf
     */
    public static String getExtension(String mineType) {
        try {
            return MimeTypes.getDefaultMimeTypes().forName(mineType).getExtension();
        } catch (MimeTypeException e) {
            log.warn("[getExtension][获取文件后缀({}) 失败]", mineType, e);
            return null;
        }
    }

    /**
     * 返回附件
     *
     * @param response 响应
     * @param filename 文件名
     * @param content  附件内容
     */
    public static void writeAttachment(HttpServletResponse response, String filename, byte[] content) throws IOException {
        // 设置 header 和 contentType
        String mineType = getMineType(content, filename);
        response.setContentType(mineType);
        // 设置内容显示、下载文件名：https://www.cnblogs.com/wq-9/articles/12165056.html
        if (isImage(mineType)) {
            // 参见 https://github.com/YunaiV/ruoyi-vue-pro/issues/692 讨论
            response.setHeader("Content-Disposition", "inline;filename=" + HttpUtils.encodeUtf8(filename));
        } else {
            response.setHeader("Content-Disposition", "attachment;filename=" + HttpUtils.encodeUtf8(filename));
        }
        // 针对 video 的特殊处理，解决视频地址在移动端播放的兼容性问题
        if (StrUtil.containsIgnoreCase(mineType, "video")) {
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("Content-Length", String.valueOf(content.length));
        }
        // 输出附件
        IoUtil.write(response.getOutputStream(), false, content);
    }

    /**
     * 判断是否是图片
     *
     * @param mineType 类型
     * @return 是否是图片
     */
    public static boolean isImage(String mineType) {
        return StrUtil.startWith(mineType, "image/");
    }

    /**
     * 判断是否可在浏览器内联预览
     *
     * 图片、音视频、PDF、纯文本按内联展示；其余类型按附件下载，避免被当作网页执行。
     *
     * @param mineType 类型
     * @return 是否可以内联预览
     */
    public static boolean isPreviewable(String mineType) {
        if (StrUtil.isEmpty(mineType)) {
            return false;
        }
        return isImage(mineType) || StrUtil.startWith(mineType, "video/")
                || StrUtil.startWith(mineType, "audio/")
                || StrUtil.equalsAny(mineType, "application/pdf", "text/plain");
    }

    /**
     * 设置内容的展示方式：预览时内联，其余按附件下载
     *
     * SVG 属于内联类型但可携带脚本，直接打开预览地址时按沙箱策略执行，避免在平台域下运行内联脚本。
     *
     * @param response 响应
     * @param filename 文件名
     * @param mineType 类型
     * @param inline   是否内联展示
     */
    public static void writeContentDisposition(HttpServletResponse response, String filename,
                                               String mineType, boolean inline) {
        response.setHeader("Content-Disposition",
                (inline ? "inline" : "attachment") + ";filename=" + HttpUtils.encodeUtf8(filename));
        if (inline && StrUtil.containsIgnoreCase(mineType, "svg")) {
            response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
        }
    }

}
