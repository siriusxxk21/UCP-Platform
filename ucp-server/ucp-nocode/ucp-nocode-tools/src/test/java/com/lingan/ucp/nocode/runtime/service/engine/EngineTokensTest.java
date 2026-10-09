package com.lingan.ucp.nocode.runtime.service.engine;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.exception.ServiceException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** 设计引擎令牌（laneEG）：签发/验签规则，以及与 Engine01（Python）签名的互通。 */
class EngineTokensTest {
    static final long NOW = 1_800_000_000L;
    static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    /** engine01_v02.embed_auth.sign_token(同一把密钥, 同一组声明) 的输出。 */
    static final String PYTHON_VECTOR =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkVHVCIsImtpZCI6IjYzMGRjZDI5NjZjNDMzNjYifQ"
                + ".eyJpc3MiOiJvcy1zZXJ2ZXIiLCJhdWQiOiJlbmdpbmUwMSIsImFwcCI6IjEwMSIsIm9iaiI6IjIwMiIsInJlYyI6IjMwMyIsInBhZ2UiOiJwYWdlLTEiLCJub2RlIjoibm9kZS0xIiwidWlkIjoiNyIsInVuIjoi5byg5LiJIiwicGVybSI6InJ3IiwiaWF0IjoxODAwMDAwMDAwLCJleHAiOjE4MDAwMDA5MDAsImp0aSI6InZlY3RvcjEifQ"
                + ".EkINTr5zWe-WPnKf-MCsrGCL0XqH8XWMdn-jWC7bR8U";

    @TempDir Path dir;

    EngineTokens tokens(String key, long now) throws Exception {
        Path file = dir.resolve("token-" + key.hashCode() + ".key");
        Files.writeString(file, key + "\n", StandardCharsets.US_ASCII);
        EngineTokens tokens = new EngineTokens();
        tokens.configure(file.toString(), 900, () -> now);
        return tokens;
    }

    static EngineTokens.Claims claims(boolean writable, long iat, long exp) {
        return new EngineTokens.Claims(
                "101", "202", "303", "page-1", "node-1", 7L, "张三", writable, iat, exp, "j1");
    }

    @Test
    void verifiesTokensSignedByTheEngineReference() throws Exception {
        EngineTokens.Claims claims = tokens(KEY, NOW + 10).verify(PYTHON_VECTOR);
        assertThat(claims)
                .extracting(
                        EngineTokens.Claims::app,
                        EngineTokens.Claims::obj,
                        EngineTokens.Claims::rec,
                        EngineTokens.Claims::uid,
                        EngineTokens.Claims::userName,
                        EngineTokens.Claims::writable)
                .containsExactly("101", "202", "303", 7L, "张三", true);
    }

    @Test
    void roundTripKeepsReadOnlyPermission() throws Exception {
        EngineTokens tokens = tokens(KEY, NOW);
        String token = tokens.sign(claims(false, NOW, NOW + 900));
        assertThat(tokens.verify(token).writable()).isFalse();
        assertThat(tokens.verify(tokens.sign(claims(true, NOW, NOW + 900))).writable()).isTrue();
    }

    @Test
    void rejectsForgedTamperedExpiredAndForeignKeyTokens() throws Exception {
        EngineTokens tokens = tokens(KEY, NOW);
        String token = tokens.sign(claims(true, NOW, NOW + 900));
        String[] parts = token.split("\\.");
        String body =
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace("\"rec\":\"303\"", "\"rec\":\"304\"");
        String tampered =
                parts[0]
                        + "."
                        + Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(body.getBytes(StandardCharsets.UTF_8))
                        + "."
                        + parts[2];
        assertThatThrownBy(() -> tokens.verify(tampered)).hasMessageContaining("签名无效");
        String none =
                Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        "{\"alg\":\"none\",\"typ\":\"EGT\"}"
                                                .getBytes(StandardCharsets.UTF_8))
                        + "."
                        + parts[1]
                        + ".";
        assertThatThrownBy(() -> tokens.verify(none)).hasMessageContaining("算法");
        assertThatThrownBy(() -> tokens(KEY, NOW + 931).verify(token)).hasMessageContaining("过期");
        assertThat(tokens(KEY, NOW + 929).verify(token).rec()).isEqualTo("303");
        String other = Base64.getEncoder().encodeToString(new byte[32]);
        assertThatThrownBy(() -> tokens(other, NOW).verify(token)).hasMessageContaining("密钥未知");
        for (String garbage : new String[] {null, "", "a.b", "a.b.c.d", "!.!.!"})
            assertThatThrownBy(() -> tokens.verify(garbage))
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getCode())
                    .isEqualTo(401);
    }

    @Test
    void rejectsFutureOverlongAndPathLikeIdentifiers() throws Exception {
        EngineTokens tokens = tokens(KEY, NOW);
        assertThatThrownBy(() -> tokens.verify(tokens.sign(claims(true, NOW + 600, NOW + 900))))
                .hasMessageContaining("时间");
        assertThatThrownBy(() -> tokens.verify(tokens.sign(claims(true, NOW, NOW + 3601))))
                .hasMessageContaining("时间");
        assertThatThrownBy(
                        () ->
                                tokens.sign(
                                        new EngineTokens.Claims(
                                                "101", "202", "../303", "p", "n", 7L, "", true, NOW,
                                                NOW + 60, "j")))
                .hasMessageContaining("标识");
    }

    @Test
    void refusesToSignWithoutKeyOrWithShortKey() throws Exception {
        EngineTokens none = new EngineTokens();
        none.configure("", 900, () -> NOW);
        assertThatThrownBy(() -> none.sign(claims(true, NOW, NOW + 60)))
                .hasMessageContaining("未配置签名密钥");
        EngineTokens shortKey = tokens(Base64.getEncoder().encodeToString(new byte[16]), NOW);
        assertThatThrownBy(() -> shortKey.sign(claims(true, NOW, NOW + 60)))
                .hasMessageContaining("密钥");
    }
}
