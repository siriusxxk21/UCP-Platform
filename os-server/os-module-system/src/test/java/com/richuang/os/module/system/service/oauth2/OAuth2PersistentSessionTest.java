package com.richuang.os.module.system.service.oauth2;

import com.richuang.os.framework.common.enums.UserTypeEnum;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.module.system.dal.dataobject.oauth2.*;
import com.richuang.os.module.system.dal.mysql.oauth2.*;
import com.richuang.os.module.system.dal.redis.oauth2.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 持续登录、过期续期和主动注销的底座回归。 */
class OAuth2PersistentSessionTest {
    private final OAuth2TokenServiceImpl service = new OAuth2TokenServiceImpl();
    private final OAuth2AccessTokenMapper accessMapper = mock(OAuth2AccessTokenMapper.class);
    private final OAuth2RefreshTokenMapper refreshMapper = mock(OAuth2RefreshTokenMapper.class);
    private final OAuth2AccessTokenRedisDAO cache = mock(OAuth2AccessTokenRedisDAO.class);
    private final OAuth2SessionRedisDAO idle = mock(OAuth2SessionRedisDAO.class);
    private final OAuth2ClientService clients = mock(OAuth2ClientService.class);

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "oauth2AccessTokenMapper", accessMapper);
        ReflectionTestUtils.setField(service, "oauth2RefreshTokenMapper", refreshMapper);
        ReflectionTestUtils.setField(service, "oauth2AccessTokenRedisDAO", cache);
        ReflectionTestUtils.setField(service, "oauth2SessionRedisDAO", idle);
        ReflectionTestUtils.setField(service, "oauth2ClientService", clients);
        ReflectionTestUtils.setField(service, "sessionIdleTimeout", Duration.ofMinutes(30));
        when(clients.validOAuthClientFromCache(anyString())).thenAnswer(call -> new OAuth2ClientDO()
                .setClientId(call.getArgument(0)).setAccessTokenValiditySeconds(1800).setRefreshTokenValiditySeconds(2592000));
    }

    private OAuth2RefreshTokenDO session(String client) {
        return new OAuth2RefreshTokenDO().setId(1L).setUserId(-1L).setUserType(UserTypeEnum.ADMIN.getValue())
                .setClientId(client).setRefreshToken("test-refresh")
                .setExpiresTime(OAuth2TokenServiceImpl.PERSISTENT_SESSION_EXPIRES_TIME);
    }

    @Test
    void loginKeepsShortAccessTokenAndPersistentRefreshToken() {
        OAuth2AccessTokenDO access = service.createAccessToken(-1L, UserTypeEnum.ADMIN.getValue(), "default", List.of());
        ArgumentCaptor<OAuth2RefreshTokenDO> saved = ArgumentCaptor.forClass(OAuth2RefreshTokenDO.class);
        verify(refreshMapper).insert(saved.capture());
        assertEquals(OAuth2TokenServiceImpl.PERSISTENT_SESSION_EXPIRES_TIME, saved.getValue().getExpiresTime());
        assertTrue(access.getExpiresTime().isBefore(LocalDateTime.now().plusHours(1)));
        verifyNoInteractions(idle);
    }

    @Test
    void refreshWorksWithoutRedisIdleKeyAndUpgradesExistingSession() {
        OAuth2RefreshTokenDO session = session("default").setExpiresTime(LocalDateTime.now().plusDays(1));
        when(refreshMapper.selectByRefreshToken("test-refresh")).thenReturn(session);
        assertNotNull(service.refreshAccessToken("test-refresh", "default").getAccessToken());
        assertEquals(OAuth2TokenServiceImpl.PERSISTENT_SESSION_EXPIRES_TIME, session.getExpiresTime());
        verify(refreshMapper).updateById(session);
        verifyNoInteractions(idle);
    }

    @Test
    void protectedRequestWorksWithoutIdleKeyButRejectsRevokedSession() {
        OAuth2AccessTokenDO access = new OAuth2AccessTokenDO().setClientId("default")
                .setUserType(UserTypeEnum.ADMIN.getValue()).setRefreshToken("test-refresh")
                .setExpiresTime(LocalDateTime.now().plusMinutes(5));
        when(cache.get("test-access")).thenReturn(access);
        when(refreshMapper.selectByRefreshToken("test-refresh")).thenReturn(session("default"));
        assertSame(access, service.checkAccessToken("test-access"));
        when(refreshMapper.selectByRefreshToken("test-refresh")).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.checkAccessToken("test-access"));
        verifyNoInteractions(idle);
    }

    @Test
    void logoutWithRefreshCredentialRemovesRotatedAccessTokens() {
        when(refreshMapper.selectByRefreshToken("test-refresh")).thenReturn(session("default"));
        when(accessMapper.selectListByRefreshToken("test-refresh")).thenReturn(List.of(
                new OAuth2AccessTokenDO().setId(2L).setAccessToken("rotated-access")));
        assertNotNull(service.removeAccessToken("test-refresh"));
        verify(refreshMapper).deleteById(1L);
        verify(cache).deleteList(java.util.Set.of("rotated-access"));
        verify(cache).delete("test-refresh");
        verify(idle).delete("test-refresh");
    }

    @Test
    void expiredHistoricalSessionCannotBeRevived() {
        when(refreshMapper.selectByRefreshToken("test-refresh")).thenReturn(
                session("default").setExpiresTime(LocalDateTime.now().minusDays(1)));
        assertThrows(ServiceException.class, () -> service.refreshAccessToken("test-refresh", "default"));
        verify(refreshMapper).deleteById(1L);
        verify(refreshMapper, never()).updateById(any(OAuth2RefreshTokenDO.class));
    }

    @Test
    void otherClientsStillRequireLiveIdleSession() {
        when(refreshMapper.selectByRefreshToken("test-refresh")).thenReturn(session("external"));
        assertThrows(ServiceException.class, () -> service.refreshAccessToken("test-refresh", "external"));
        verify(idle).touch("test-refresh", Duration.ofMinutes(30));
    }
}
