package com.richuang.os.module.system.feedback.service;

import cn.hutool.core.lang.Assert;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * 校验反馈图片的数量、大小、声明类型和文件签名，阻止伪装文件进入消息详情。
 */
@Component
public class FeedbackImageValidator {

    public static final int MAX_IMAGES = 3;
    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    public static final List<String> ALLOWED_TYPES = List.of("image/jpeg", "image/png", "image/webp");

    public void validate(List<MultipartFile> images) {
        Assert.isTrue(images.size() <= MAX_IMAGES, "最多上传3张图片");
        for (MultipartFile image : images) {
            Assert.isTrue(!image.isEmpty(), "反馈图片不能为空");
            Assert.isTrue(image.getSize() <= MAX_IMAGE_BYTES, "单张图片不能超过5MB");
            String contentType = image.getContentType() == null
                    ? "" : image.getContentType().toLowerCase(Locale.ROOT);
            Assert.isTrue(ALLOWED_TYPES.contains(contentType), "仅支持JPG、PNG和WebP图片");
            Assert.isTrue(matchesSignature(image, contentType), "图片内容与文件类型不匹配");
        }
    }

    private boolean matchesSignature(MultipartFile image, String contentType) {
        try {
            byte[] bytes = image.getBytes();
            return switch (contentType) {
                case "image/jpeg" -> bytes.length >= 3
                        && unsigned(bytes[0]) == 0xFF && unsigned(bytes[1]) == 0xD8 && unsigned(bytes[2]) == 0xFF;
                case "image/png" -> bytes.length >= 8
                        && unsigned(bytes[0]) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
                        && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A;
                case "image/webp" -> bytes.length >= 12
                        && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP");
                default -> false;
            };
        } catch (IOException exception) {
            throw new IllegalArgumentException("反馈图片读取失败", exception);
        }
    }

    private int unsigned(byte value) {
        return value & 0xFF;
    }

    private boolean ascii(byte[] bytes, int offset, String expected) {
        for (int index = 0; index < expected.length(); index++) {
            if (bytes[offset + index] != expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }
}
