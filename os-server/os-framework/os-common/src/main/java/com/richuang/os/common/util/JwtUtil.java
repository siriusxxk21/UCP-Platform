package com.richuang.os.common.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expiration;

    public String generateToken(String userId, String username) {
        return generateToken(userId, username, null);
    }

    public String generateToken(String userId, String username, String tenantId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        var builder = JWT.create()
                .withSubject(String.valueOf(userId))
                .withClaim("username", username)
                .withIssuedAt(now)
                .withExpiresAt(expiryDate);

        if (tenantId != null) {
            builder.withClaim("tenantId", tenantId);
        }

        return builder.sign(Algorithm.HMAC256(secret));
    }

    public DecodedJWT verifyToken(String token) {
        try {
            JWTVerifier verifier = JWT.require(Algorithm.HMAC256(secret)).build();
            return verifier.verify(token);
        } catch (JWTVerificationException e) {
            return null;
        }
    }

    public String getUserIdFromToken(String token) {
        DecodedJWT jwt = verifyToken(token);
        if (jwt != null) {
            return jwt.getSubject();
        }
        return null;
    }

    public String getUsernameFromToken(String token) {
        DecodedJWT jwt = verifyToken(token);
        if (jwt != null) {
            return jwt.getClaim("username").asString();
        }
        return null;
    }

    public String getTenantIdFromToken(String token) {
        DecodedJWT jwt = verifyToken(token);
        if (jwt != null) {
            return jwt.getClaim("tenantId").asString();
        }
        return null;
    }

    /**
     * 获取 Token 过期时间
     */
    public Date getExpirationDateFromToken(String token) {
        DecodedJWT jwt = verifyToken(token);
        if (jwt != null) {
            return jwt.getExpiresAt();
        }
        return null;
    }

    /**
     * 检查 Token 是否需要续期（剩余时间小于阈值）
     *
     * @param token       当前 Token
     * @param thresholdMs 续期阈值（毫秒）
     * @return true-需要续期
     */
    public boolean shouldRefreshToken(String token, long thresholdMs) {
        Date expiration = getExpirationDateFromToken(token);
        if (expiration == null) {
            return false;
        }
        long remainingTime = expiration.getTime() - System.currentTimeMillis();
        return remainingTime < thresholdMs;
    }
}
