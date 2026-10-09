package com.lingan.ucp.nocode.runtime.service.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lingan.ucp.framework.common.exception.ServiceException;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 设计引擎令牌（laneEG，DESIGN.md §1.2）：HS256 紧凑 JWS，{@code header={alg:HS256, typ:EGT, kid}}。
 *
 * <p>密钥只从文件读（{@code os.nocode.engine.token-key-file}，base64，≥32 字节），与引擎同一把；文件修改后自动重读。 验签：算法固定
 * HS256、kid 必须是当前密钥、常数时间比较、iss/aud 固定、未过期（容差 30 秒）、寿命不超过 1 小时、各标识符合白名单。 令牌只是「谁、哪条记录、
 * 可读/可写」的凭证，engine-link 接口每次仍按令牌用户实时复查权限。
 */
@Component
public class EngineTokens {
    static final String ISSUER = "os-server";
    static final String AUDIENCE = "engine01";
    static final long LEEWAY_SECONDS = 30;
    static final long MAX_LIFETIME_SECONDS = 3600;
    static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final int UNAUTHORIZED = 401;

    /** 已验签的声明。uid 为系统用户 ID。 */
    public record Claims(
            String app,
            String obj,
            String rec,
            String page,
            String node,
            long uid,
            String userName,
            boolean writable,
            long iat,
            long exp,
            String jti) {}

    @Value("${os.nocode.engine.token-key-file:}")
    private String keyFile;

    @Value("${os.nocode.engine.token-ttl-seconds:900}")
    private long ttlSeconds;

    private ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    private LongSupplier clock = () -> System.currentTimeMillis() / 1000;
    private volatile CachedKey cached;

    private record CachedKey(String path, long modified, byte[] key, String kid) {}

    @PostConstruct
    void init() {
        json = new ObjectMapper();
    }

    /** 测试用：固定时钟与密钥文件。 */
    void configure(String keyFile, long ttlSeconds, LongSupplier clock) {
        this.keyFile = keyFile;
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
        this.cached = null;
        if (json == null) init();
    }

    public boolean configured() {
        return keyFile != null && !keyFile.isBlank();
    }

    long ttl() {
        return Math.max(60, Math.min(MAX_LIFETIME_SECONDS, ttlSeconds));
    }

    long now() {
        return clock.getAsLong();
    }

    public String sign(Claims claims) {
        for (String id :
                new String[] {
                    claims.app(), claims.obj(), claims.rec(), claims.page(), claims.node()
                })
            if (id == null || !ID.matcher(id).matches())
                throw new ServiceException(400, "设计引擎：记录或页面标识不支持");
        CachedKey key = key();
        ObjectNode header =
                json.createObjectNode().put("alg", "HS256").put("typ", "EGT").put("kid", key.kid());
        ObjectNode body =
                json.createObjectNode()
                        .put("iss", ISSUER)
                        .put("aud", AUDIENCE)
                        .put("app", claims.app())
                        .put("obj", claims.obj())
                        .put("rec", claims.rec())
                        .put("page", claims.page())
                        .put("node", claims.node())
                        .put("uid", Long.toString(claims.uid()))
                        .put("un", claims.userName() == null ? "" : claims.userName())
                        .put("perm", claims.writable() ? "rw" : "r")
                        .put("iat", claims.iat())
                        .put("exp", claims.exp())
                        .put("jti", claims.jti());
        String input = b64(bytes(header)) + "." + b64(bytes(body));
        return input + "." + b64(hmac(key.key(), input));
    }

    public String newTokenId() {
        byte[] raw = new byte[12];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public Claims verify(String token) {
        if (token == null || token.isBlank() || token.length() > 4096) throw denied("缺少设计引擎令牌");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) throw denied("设计引擎令牌格式无效");
        CachedKey key = key();
        JsonNode header = parse(parts[0]);
        if (!"HS256".equals(header.path("alg").asText(null))
                || !"EGT".equals(header.path("typ").asText(null))) throw denied("设计引擎令牌算法不被接受");
        if (!key.kid().equals(header.path("kid").asText(null))) throw denied("设计引擎令牌密钥未知");
        byte[] expected = hmac(key.key(), parts[0] + "." + parts[1]);
        if (!MessageDigest.isEqual(expected, decode(parts[2]))) throw denied("设计引擎令牌签名无效");
        JsonNode body = parse(parts[1]);
        if (!ISSUER.equals(body.path("iss").asText(null))
                || !AUDIENCE.equals(body.path("aud").asText(null))) throw denied("设计引擎令牌签发方或受众不符");
        if (!body.path("iat").isIntegralNumber() || !body.path("exp").isIntegralNumber())
            throw denied("设计引擎令牌时间无效");
        long iat = body.path("iat").asLong(), exp = body.path("exp").asLong(), now = now();
        if (exp + LEEWAY_SECONDS < now) throw denied("设计引擎令牌已过期");
        if (iat - LEEWAY_SECONDS > now || exp <= iat || exp - iat > MAX_LIFETIME_SECONDS)
            throw denied("设计引擎令牌时间无效");
        String perm = body.path("perm").asText("");
        if (!perm.equals("r") && !perm.equals("rw")) throw denied("设计引擎令牌权限无效");
        String[] ids = new String[6];
        String[] names = {"app", "obj", "rec", "page", "node", "jti"};
        for (int i = 0; i < names.length; i++) {
            ids[i] = body.path(names[i]).asText(null);
            if (ids[i] == null || !ID.matcher(ids[i]).matches()) throw denied("设计引擎令牌声明无效");
        }
        long uid;
        try {
            uid = Long.parseLong(body.path("uid").asText(""));
        } catch (NumberFormatException e) {
            throw denied("设计引擎令牌声明无效");
        }
        return new Claims(
                ids[0],
                ids[1],
                ids[2],
                ids[3],
                ids[4],
                uid,
                body.path("un").asText(""),
                perm.equals("rw"),
                iat,
                exp,
                ids[5]);
    }

    private CachedKey key() {
        if (!configured())
            throw new ServiceException(400, "设计引擎未配置签名密钥（os.nocode.engine.token-key-file）");
        Path path = Path.of(keyFile);
        try {
            long modified = Files.getLastModifiedTime(path).toMillis();
            CachedKey current = cached;
            if (current != null && current.path().equals(keyFile) && current.modified() == modified)
                return current;
            String text = Files.readString(path, StandardCharsets.US_ASCII).trim();
            byte[] key = Base64.getDecoder().decode(text.replace('-', '+').replace('_', '/'));
            if (key.length < 32) throw new ServiceException(400, "设计引擎签名密钥不足 32 字节");
            String kid =
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(key))
                            .substring(0, 16);
            CachedKey loaded = new CachedKey(keyFile, modified, key, kid);
            cached = loaded;
            return loaded;
        } catch (IOException | IllegalArgumentException | GeneralSecurityException e) {
            // 不把路径细节以外的内容写进报错；密钥本身不出现在任何日志或响应里。
            throw new ServiceException(400, "设计引擎签名密钥读取失败");
        }
    }

    private static byte[] hmac(byte[] key, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private byte[] bytes(JsonNode node) {
        try {
            return json.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode parse(String segment) {
        try {
            JsonNode node = json.readTree(decode(segment));
            if (node == null || !node.isObject()) throw denied("设计引擎令牌格式无效");
            return node;
        } catch (IOException e) {
            throw denied("设计引擎令牌格式无效");
        }
    }

    private static byte[] decode(String segment) {
        if (!SEGMENT.matcher(segment).matches()) throw denied("设计引擎令牌格式无效");
        try {
            return Base64.getUrlDecoder().decode(segment);
        } catch (IllegalArgumentException e) {
            throw denied("设计引擎令牌格式无效");
        }
    }

    private static String b64(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    static ServiceException denied(String message) {
        return new ServiceException(UNAUTHORIZED, message);
    }
}
