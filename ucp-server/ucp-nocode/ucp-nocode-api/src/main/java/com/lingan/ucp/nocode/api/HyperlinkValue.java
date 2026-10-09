package com.lingan.ucp.nocode.api;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import java.net.URI;
import java.util.*;

/** 链接统一保存地址与文字；不会请求或下载用户填写的地址。 */
public final class HyperlinkValue {
    private HyperlinkValue() {}

    public static Map<String, String> normalize(Object raw) {
        if (raw == null || "".equals(raw)) return null;
        String link, text;
        if (raw instanceof String value) {
            link = value.trim();
            text = "";
        } else if (raw instanceof Map<?, ?> map
                && Set.of("text", "link").containsAll(map.keySet())
                && map.get("link") instanceof String address
                && (map.get("text") == null || map.get("text") instanceof String)) {
            link = address.trim();
            text = Objects.toString(map.get("text"), "").trim();
        } else throw invalid("超链接应填写地址及可选显示文字");
        if (link.isEmpty() && text.isEmpty()) return null;
        if (link.length() > 4096 || text.length() > 500)
            throw invalid("超链接地址最多 4096 字符，显示文字最多 500 字符");
        try {
            var uri = URI.create(link);
            if (!Set.of("http", "https")
                            .contains(
                                    Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT))
                    || link.contains("\\")) throw new IllegalArgumentException();
            // URL 仅解析结构，不发起连接；兼容浏览器可识别的国际化域名。
            var url = uri.toURL();
            if (url.getHost().isBlank() || url.getUserInfo() != null || url.getPort() > 65535)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException | java.net.MalformedURLException error) {
            throw invalid("请填写有效的 HTTP 或 HTTPS 链接地址");
        }
        return Map.of("link", link, "text", text);
    }
}
