package com.richuang.os.module.system.dal.redis.oauth2;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static com.richuang.os.module.system.dal.redis.RedisKeyConstants.OAUTH2_SESSION;

/**
 * OAuth2 登录会话的空闲超时控制。
 */
@Repository
public class OAuth2SessionRedisDAO {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void create(String refreshToken, Duration idleTimeout) {
        stringRedisTemplate.opsForValue().set(formatKey(refreshToken), "1", idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 续期已存在的会话。不存在时返回 false，避免超时会话被请求重新激活。
     */
    public boolean touch(String refreshToken, Duration idleTimeout) {
        return Boolean.TRUE.equals(stringRedisTemplate.expire(
                formatKey(refreshToken), idleTimeout.toMillis(), TimeUnit.MILLISECONDS));
    }

    public void delete(String refreshToken) {
        stringRedisTemplate.delete(formatKey(refreshToken));
    }

    private static String formatKey(String refreshToken) {
        return String.format(OAUTH2_SESSION, refreshToken);
    }

}
