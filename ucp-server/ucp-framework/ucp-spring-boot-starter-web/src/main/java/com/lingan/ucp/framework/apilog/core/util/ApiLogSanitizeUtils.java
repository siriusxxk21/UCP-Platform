package com.lingan.ucp.framework.apilog.core.util;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lingan.ucp.framework.common.util.json.JsonUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * API 日志脱敏工具：访问日志打印（ApiAccessLogInterceptor）、访问日志落库（ApiAccessLogFilter）、
 * 错误日志落库（GlobalExceptionHandler）共用同一份敏感键表与同一套脱敏逻辑。
 *
 * 键名大小写不敏感；命中的键保留、值替换为 {@link #MASK}；JSON 嵌套对象与数组递归处理。
 *
 * @author os
 */
public final class ApiLogSanitizeUtils {

    /**
     * 脱敏后的占位值
     */
    public static final String MASK = "******";

    /**
     * 敏感键表（小写存放，匹配时忽略大小写）
     */
    private static final Set<String> SANITIZE_KEYS = Stream.of(
                    "password", "oldPassword", "newPassword", "confirmPassword", "pwd", "passwd",
                    "token", "accessToken", "refreshToken", "passwordChangeToken",
                    "secret", "clientSecret", "apiSecret", "apiKey",
                    "captchaVerification", "authorization")
            .map(key -> key.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    private ApiLogSanitizeUtils() {
    }

    /**
     * 判断键名是否敏感
     *
     * @param key 键名
     * @param extraKeys 额外的敏感键（例如 {@code @ApiAccessLog#sanitizeKeys}），可为空
     * @return 是否敏感
     */
    public static boolean isSensitiveKey(String key, String... extraKeys) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (SANITIZE_KEYS.contains(lower)) {
            return true;
        }
        if (extraKeys == null) {
            return false;
        }
        for (String extraKey : extraKeys) {
            if (extraKey != null && lower.equals(extraKey.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 脱敏 query 参数，返回新 Map（不修改入参）
     */
    public static Map<String, String> sanitizeParamMap(Map<String, String> params, String... extraKeys) {
        if (params == null || params.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>(params.size());
        params.forEach((key, value) -> result.put(key, isSensitiveKey(key, extraKeys) ? MASK : value));
        return result;
    }

    /**
     * 脱敏 JSON 字符串，返回脱敏后的 JSON 字符串
     *
     * 无法解析为 JSON 时不返回原文（原文里可能就有明文密码），只返回长度说明。
     */
    public static String sanitizeJson(String jsonString, String... extraKeys) {
        if (StrUtil.isEmpty(jsonString)) {
            return jsonString;
        }
        // 用 Quietly 解析：JsonUtils.parseTree 解析失败时会把原文打到 ERROR 日志
        JsonNode rootNode = JsonUtils.parseObjectQuietly(jsonString, JsonNode.class);
        if (rootNode == null) {
            return "[unparseable json omitted, length=" + jsonString.length() + "]";
        }
        sanitizeJsonNode(rootNode, extraKeys);
        return JsonUtils.toJsonString(rootNode);
    }

    /**
     * 原地脱敏 JsonNode（调用方传入自己解析出来的树）
     */
    public static void sanitizeJsonNode(JsonNode node, String... extraKeys) {
        if (node == null) {
            return;
        }
        // 情况一：数组，遍历处理
        if (node.isArray()) {
            for (JsonNode childNode : node) {
                sanitizeJsonNode(childNode, extraKeys);
            }
            return;
        }
        // 情况二：非 Object，只是某个值，直接返回
        if (!node.isObject()) {
            return;
        }
        // 情况三：Object，遍历处理（先拷贝键名，避免遍历中修改）
        ObjectNode objectNode = (ObjectNode) node;
        List<String> fieldNames = new ArrayList<>();
        objectNode.fieldNames().forEachRemaining(fieldNames::add);
        for (String fieldName : fieldNames) {
            JsonNode value = objectNode.get(fieldName);
            if (isSensitiveKey(fieldName, extraKeys)) {
                if (value != null && !value.isNull()) {
                    objectNode.put(fieldName, MASK);
                }
                continue;
            }
            sanitizeJsonNode(value, extraKeys);
        }
    }

}
