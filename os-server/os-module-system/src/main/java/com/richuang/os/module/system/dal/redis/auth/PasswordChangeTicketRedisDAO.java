package com.richuang.os.module.system.dal.redis.auth;

import cn.hutool.core.util.IdUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.richuang.os.module.system.dal.redis.RedisKeyConstants.PASSWORD_CHANGE_TICKET;

/**
 * 强制改密凭证的 Redis 访问层。
 *
 * 每个用户只保留一个短时凭证，消费时通过 Lua 原子比较并删除，避免重复使用。
 */
@Repository
public class PasswordChangeTicketRedisDAO {

    private static final Duration TICKET_TTL = Duration.ofMinutes(5);

    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 创建凭证。凭证包含用户编号仅用于定位 Redis key，随机部分负责保证不可猜测性。
     */
    public String create(Long userId) {
        String ticket = userId + "." + IdUtil.fastSimpleUUID();
        stringRedisTemplate.opsForValue().set(formatKey(userId), ticket,
                TICKET_TTL.toMillis(), TimeUnit.MILLISECONDS);
        return ticket;
    }

    /**
     * 原子消费凭证，成功返回用户编号，凭证无效、过期或已被替换时返回 {@code null}。
     */
    public Long consume(String ticket) {
        Long userId = parseUserId(ticket);
        if (userId == null) {
            return null;
        }
        Long deleted = stringRedisTemplate.execute(CONSUME_SCRIPT, List.of(formatKey(userId)), ticket);
        return Long.valueOf(1L).equals(deleted) ? userId : null;
    }

    static Long parseUserId(String ticket) {
        if (ticket == null) {
            return null;
        }
        int separatorIndex = ticket.indexOf('.');
        if (separatorIndex <= 0 || separatorIndex == ticket.length() - 1) {
            return null;
        }
        try {
            return Long.valueOf(ticket.substring(0, separatorIndex));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String formatKey(Long userId) {
        return String.format(PASSWORD_CHANGE_TICKET, userId);
    }

}
