package com.richuang.os.module.system.service.auth;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richuang.os.framework.common.enums.CommonStatusEnum;
import com.richuang.os.framework.common.enums.UserTypeEnum;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.module.system.api.sms.SmsCodeApi;
import com.richuang.os.module.system.controller.admin.auth.vo.*;
import com.richuang.os.module.system.controller.admin.user.vo.profile.PasswordStatusVO;
import com.richuang.os.module.system.controller.admin.user.vo.profile.UserProfileUpdatePasswordReqVO;
import com.richuang.os.module.system.dal.dataobject.oauth2.OAuth2AccessTokenDO;
import com.richuang.os.module.system.dal.dataobject.user.AdminUserDO;
import com.richuang.os.module.system.dal.mysql.user.AdminUserMapper;
import com.richuang.os.module.system.dal.redis.auth.PasswordChangeTicketRedisDAO;
import com.richuang.os.module.system.service.logger.LoginLogService;
import com.richuang.os.module.system.service.oauth2.OAuth2TokenService;
import com.richuang.os.module.system.service.user.AdminUserService;
import com.richuang.os.module.system.service.user.AdminUserServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 登录、强制改密及密码变化后会话安全策略单元测试。
 */
class AdminAuthServiceImplTest {

    private final AdminUserService userService = mock(AdminUserService.class);
    private final OAuth2TokenService oauth2TokenService = mock(OAuth2TokenService.class);
    private final PasswordChangeTicketRedisDAO ticketRedisDAO = mock(PasswordChangeTicketRedisDAO.class);
    private final LoginLogService loginLogService = mock(LoginLogService.class);
    private final SmsCodeApi smsCodeApi = mock(SmsCodeApi.class);

    private AdminAuthServiceImpl authService;

    @BeforeAll
    static void initTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AdminUserDO.class);
    }

    @BeforeEach
    void setUp() {
        authService = new AdminAuthServiceImpl();
        authService.setCaptchaEnable(false);
        ReflectionTestUtils.setField(authService, "userService", userService);
        ReflectionTestUtils.setField(authService, "oauth2TokenService", oauth2TokenService);
        ReflectionTestUtils.setField(authService, "passwordChangeTicketRedisDAO", ticketRedisDAO);
        ReflectionTestUtils.setField(authService, "loginLogService", loginLogService);
        ReflectionTestUtils.setField(authService, "smsCodeApi", smsCodeApi);
    }

    @Test
    void loginShouldCreateFormalTokenWhenPasswordIsValid() {
        AdminUserDO user = enabledUser();
        when(userService.getUserByUsername("user")).thenReturn(user);
        when(userService.isPasswordMatch("old-password", user.getPassword())).thenReturn(true);
        when(userService.getPasswordStatus(user.getId())).thenReturn(passwordStatus(false));
        when(oauth2TokenService.createAccessToken(eq(user.getId()), eq(UserTypeEnum.ADMIN.getValue()),
                anyString(), isNull())).thenReturn(new OAuth2AccessTokenDO()
                .setUserId(user.getId()).setAccessToken("access").setRefreshToken("refresh")
                .setExpiresTime(LocalDateTime.now().plusHours(1)));

        AuthLoginRespVO result = authService.login(loginRequest());

        assertEquals(AuthLoginStatusEnum.SUCCESS, result.getLoginStatus());
        assertEquals("access", result.getAccessToken());
        assertNull(result.getPasswordChangeToken());
        verify(ticketRedisDAO, never()).create(anyLong());
    }

    @Test
    void loginShouldOnlyCreateTicketWhenPasswordMustChange() {
        AdminUserDO user = enabledUser();
        when(userService.getUserByUsername("user")).thenReturn(user);
        when(userService.isPasswordMatch("old-password", user.getPassword())).thenReturn(true);
        when(userService.getPasswordStatus(user.getId())).thenReturn(passwordStatus(true));
        when(ticketRedisDAO.create(user.getId())).thenReturn("1.ticket");

        AuthLoginRespVO result = authService.login(loginRequest());

        assertEquals(AuthLoginStatusEnum.PASSWORD_CHANGE_REQUIRED, result.getLoginStatus());
        assertEquals("1.ticket", result.getPasswordChangeToken());
        assertNull(result.getAccessToken());
        assertNull(result.getRefreshToken());
        verify(oauth2TokenService).removeAccessToken(user.getId(), UserTypeEnum.ADMIN.getValue());
        verify(oauth2TokenService, never()).createAccessToken(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void smsLoginShouldUseTheSamePasswordChangeFlow() {
        AdminUserDO user = enabledUser();
        when(userService.getUserByMobile("13800138000")).thenReturn(user);
        when(userService.getPasswordStatus(user.getId())).thenReturn(passwordStatus(true));
        when(ticketRedisDAO.create(user.getId())).thenReturn("1.sms-ticket");
        AuthSmsLoginReqVO request = AuthSmsLoginReqVO.builder().mobile("13800138000").code("1234").build();

        AuthLoginRespVO result = authService.smsLogin(request);

        assertEquals(AuthLoginStatusEnum.PASSWORD_CHANGE_REQUIRED, result.getLoginStatus());
        assertEquals("1.sms-ticket", result.getPasswordChangeToken());
        verify(oauth2TokenService, never()).createAccessToken(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void changeRequiredPasswordShouldConsumeTicketUpdatePasswordAndCreateFormalToken() {
        AdminUserDO user = enabledUser();
        when(ticketRedisDAO.consume("1.ticket")).thenReturn(1L);
        when(userService.getUser(1L)).thenReturn(user);
        when(userService.getPasswordStatus(1L)).thenReturn(passwordStatus(true));
        when(oauth2TokenService.createAccessToken(eq(1L), eq(UserTypeEnum.ADMIN.getValue()),
                anyString(), isNull())).thenReturn(new OAuth2AccessTokenDO()
                .setUserId(1L).setAccessToken("new-access").setRefreshToken("new-refresh")
                .setExpiresTime(LocalDateTime.now().plusHours(1)));
        AuthChangeRequiredPasswordReqVO request = changePasswordRequest("1.ticket");

        AuthLoginRespVO result = authService.changeRequiredPassword(request);

        verify(userService).updateRequiredPassword(1L, "NewPassword1");
        assertEquals(AuthLoginStatusEnum.SUCCESS, result.getLoginStatus());
        assertEquals("new-access", result.getAccessToken());
        assertEquals("new-refresh", result.getRefreshToken());
        assertNull(result.getPasswordChangeToken());
    }

    @Test
    void changeRequiredPasswordShouldRejectExpiredOrReusedTicket() {
        when(ticketRedisDAO.consume("expired")).thenReturn(null);

        assertThrows(ServiceException.class,
                () -> authService.changeRequiredPassword(changePasswordRequest("expired")));
        verify(userService, never()).updateRequiredPassword(anyLong(), anyString());
        verify(oauth2TokenService, never()).createAccessToken(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void changeRequiredPasswordShouldRejectDisabledUser() {
        AdminUserDO user = enabledUser().setStatus(CommonStatusEnum.DISABLE.getStatus());
        when(ticketRedisDAO.consume("1.ticket")).thenReturn(1L);
        when(userService.getUser(1L)).thenReturn(user);
        when(userService.getPasswordStatus(1L)).thenReturn(passwordStatus(true));

        assertThrows(ServiceException.class,
                () -> authService.changeRequiredPassword(changePasswordRequest("1.ticket")));
        verify(userService, never()).updateRequiredPassword(anyLong(), anyString());
        verify(oauth2TokenService, never()).createAccessToken(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void changeRequiredPasswordShouldRejectUserWhosePasswordNoLongerRequiresChange() {
        when(ticketRedisDAO.consume("1.ticket")).thenReturn(1L);
        when(userService.getUser(1L)).thenReturn(enabledUser());
        when(userService.getPasswordStatus(1L)).thenReturn(passwordStatus(false));

        assertThrows(ServiceException.class,
                () -> authService.changeRequiredPassword(changePasswordRequest("1.ticket")));
        verify(userService, never()).updateRequiredPassword(anyLong(), anyString());
        verify(oauth2TokenService, never()).createAccessToken(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void personalPasswordChangeShouldRevokeAllSessions() {
        PasswordServiceFixture fixture = passwordServiceFixture();
        UserProfileUpdatePasswordReqVO request = new UserProfileUpdatePasswordReqVO();
        request.setOldPassword("OldPassword1");
        request.setNewPassword("NewPassword1");

        fixture.service().updateUserPassword(1L, request);

        verify(fixture.tokenService()).removeAccessToken(1L, UserTypeEnum.ADMIN.getValue());
    }

    @Test
    void administratorPasswordResetShouldRevokeAllSessions() {
        PasswordServiceFixture fixture = passwordServiceFixture();

        fixture.service().updateUserPassword(1L, "TemporaryPassword1");

        verify(fixture.tokenService()).removeAccessToken(1L, UserTypeEnum.ADMIN.getValue());
    }

    @Test
    void recoveredPasswordShouldRevokeAllSessions() {
        PasswordServiceFixture fixture = passwordServiceFixture();

        fixture.service().resetUserPassword(1L, "RecoveredPassword1");

        verify(fixture.tokenService()).removeAccessToken(1L, UserTypeEnum.ADMIN.getValue());
    }

    private static AdminUserDO enabledUser() {
        return new AdminUserDO().setId(1L).setUsername("user").setPassword("encoded")
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
    }

    private static PasswordStatusVO passwordStatus(boolean mustChange) {
        return PasswordStatusVO.builder().mustChange(mustChange).build();
    }

    private static AuthLoginReqVO loginRequest() {
        AuthLoginReqVO request = new AuthLoginReqVO();
        request.setUsername("user");
        request.setPassword("old-password");
        return request;
    }

    private static AuthChangeRequiredPasswordReqVO changePasswordRequest(String ticket) {
        AuthChangeRequiredPasswordReqVO request = new AuthChangeRequiredPasswordReqVO();
        request.setPasswordChangeToken(ticket);
        request.setNewPassword("NewPassword1");
        return request;
    }

    private static PasswordServiceFixture passwordServiceFixture() {
        AdminUserMapper mapper = mock(AdminUserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        OAuth2TokenService tokenService = mock(OAuth2TokenService.class);
        AdminUserServiceImpl service = new AdminUserServiceImpl();
        ReflectionTestUtils.setField(service, "userMapper", mapper);
        ReflectionTestUtils.setField(service, "passwordEncoder", encoder);
        ReflectionTestUtils.setField(service, "oauth2TokenService", tokenService);
        when(mapper.selectById(1L)).thenReturn(
                new AdminUserDO().setId(1L).setNickname("测试用户").setPassword("encoded-old"));
        when(encoder.matches("OldPassword1", "encoded-old")).thenReturn(true);
        when(encoder.encode(anyString())).thenReturn("encoded-new");
        return new PasswordServiceFixture(service, tokenService);
    }

    private record PasswordServiceFixture(AdminUserServiceImpl service, OAuth2TokenService tokenService) {
    }

}
