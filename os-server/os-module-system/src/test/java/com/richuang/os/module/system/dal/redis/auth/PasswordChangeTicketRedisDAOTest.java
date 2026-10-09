package com.richuang.os.module.system.dal.redis.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 改密凭证格式校验测试， malformed 输入必须在访问 Redis 前被拒绝。
 */
class PasswordChangeTicketRedisDAOTest {

    @Test
    void parseUserIdShouldAcceptValidTicket() {
        assertEquals(42L, PasswordChangeTicketRedisDAO.parseUserId("42.0123456789abcdef"));
    }

    @Test
    void parseUserIdShouldRejectMalformedTicket() {
        assertNull(PasswordChangeTicketRedisDAO.parseUserId(null));
        assertNull(PasswordChangeTicketRedisDAO.parseUserId(""));
        assertNull(PasswordChangeTicketRedisDAO.parseUserId("42"));
        assertNull(PasswordChangeTicketRedisDAO.parseUserId("user.token"));
        assertNull(PasswordChangeTicketRedisDAO.parseUserId("42."));
    }

}
