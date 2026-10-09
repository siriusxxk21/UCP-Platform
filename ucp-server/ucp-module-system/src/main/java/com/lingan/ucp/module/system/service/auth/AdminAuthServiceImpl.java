package com.lingan.ucp.module.system.service.auth;

import cn.hutool.core.util.ObjectUtil;
import com.lingan.ucp.framework.common.enums.CommonStatusEnum;
import com.lingan.ucp.framework.common.enums.UserTypeEnum;
import com.lingan.ucp.framework.common.util.monitor.TracerUtils;
import com.lingan.ucp.framework.common.util.object.BeanUtils;
import com.lingan.ucp.framework.common.util.servlet.ServletUtils;
import com.lingan.ucp.framework.common.util.validation.ValidationUtils;
import com.lingan.ucp.framework.datapermission.core.annotation.DataPermission;
import com.lingan.ucp.module.system.api.logger.dto.LoginLogCreateReqDTO;
import com.lingan.ucp.module.system.api.sms.SmsCodeApi;
import com.lingan.ucp.module.system.api.sms.dto.code.SmsCodeUseReqDTO;
import com.lingan.ucp.module.system.controller.admin.auth.vo.*;
import com.lingan.ucp.module.system.controller.admin.user.vo.profile.PasswordStatusVO;
import com.lingan.ucp.module.system.convert.auth.AuthConvert;
import com.lingan.ucp.module.system.dal.dataobject.oauth2.OAuth2AccessTokenDO;
import com.lingan.ucp.module.system.dal.dataobject.user.AdminUserDO;
import com.lingan.ucp.module.system.dal.redis.auth.PasswordChangeTicketRedisDAO;
import com.lingan.ucp.module.system.enums.logger.LoginLogTypeEnum;
import com.lingan.ucp.module.system.enums.logger.LoginResultEnum;
import com.lingan.ucp.module.system.enums.oauth2.OAuth2ClientConstants;
import com.lingan.ucp.module.system.enums.sms.SmsSceneEnum;
import com.lingan.ucp.module.system.service.logger.LoginLogService;
import com.lingan.ucp.module.system.service.member.MemberService;
import com.lingan.ucp.module.system.service.oauth2.OAuth2TokenService;
import com.lingan.ucp.module.system.service.user.AdminUserService;
import com.anji.captcha.model.common.ResponseModel;
import com.anji.captcha.model.vo.CaptchaVO;
import com.anji.captcha.service.CaptchaService;
import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.Resource;
import jakarta.validation.Validator;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

import static com.lingan.ucp.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.lingan.ucp.framework.common.util.servlet.ServletUtils.getClientIP;
import static com.lingan.ucp.module.system.enums.ErrorCodeConstants.*;

/**
 * Auth Service 实现类
 *
 * @author os
 */
@Service
@Slf4j
public class AdminAuthServiceImpl implements AdminAuthService {

    @Resource
    private AdminUserService userService;
    @Resource
    private LoginLogService loginLogService;
    @Resource
    private OAuth2TokenService oauth2TokenService;
    @Resource
    private PasswordChangeTicketRedisDAO passwordChangeTicketRedisDAO;
    @Resource
    private MemberService memberService;
    @Resource
    private Validator validator;
    @Resource
    private CaptchaService captchaService;
    @Resource
    private SmsCodeApi smsCodeApi;

    /**
     * 验证码的开关，默认为 true
     */
    @Value("${os.captcha.enable:true}")
    @Setter // 为了单测：开启或者关闭验证码
    private Boolean captchaEnable;

    @Override
    public AdminUserDO authenticate(String username, String password) {
        final LoginLogTypeEnum logTypeEnum = LoginLogTypeEnum.LOGIN_USERNAME;
        // 校验账号是否存在
        AdminUserDO user = userService.getUserByUsername(username);
        if (user == null) {
            createLoginLog(null, username, logTypeEnum, LoginResultEnum.BAD_CREDENTIALS);
            throw exception(AUTH_LOGIN_BAD_CREDENTIALS);
        }
        if (!userService.isPasswordMatch(password, user.getPassword())) {
            createLoginLog(user.getId(), username, logTypeEnum, LoginResultEnum.BAD_CREDENTIALS);
            throw exception(AUTH_LOGIN_BAD_CREDENTIALS);
        }
        // 校验是否禁用
        if (CommonStatusEnum.isDisable(user.getStatus())) {
            createLoginLog(user.getId(), username, logTypeEnum, LoginResultEnum.USER_DISABLED);
            throw exception(AUTH_LOGIN_USER_DISABLED);
        }
        return user;
    }

    @Override
    @DataPermission(enable = false)
    public AuthLoginRespVO login(AuthLoginReqVO reqVO) {
        // 校验验证码
        validateCaptcha(reqVO);

        // 使用账号密码，进行登录
        AdminUserDO user = authenticate(reqVO.getUsername(), reqVO.getPassword());

        return createLoginResult(user, reqVO.getUsername(), LoginLogTypeEnum.LOGIN_USERNAME);
    }

    @Override
    public void sendSmsCode(AuthSmsSendReqVO reqVO) {
        // 如果是重置密码场景，需要校验图形验证码是否正确
        if (Objects.equals(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene(), reqVO.getScene())) {
            ResponseModel response = doValidateCaptcha(reqVO);
            if (!response.isSuccess()) {
                throw exception(AUTH_REGISTER_CAPTCHA_CODE_ERROR, response.getRepMsg());
            }
        }

        // 登录场景，验证是否存在
        if (userService.getUserByMobile(reqVO.getMobile()) == null) {
            throw exception(AUTH_MOBILE_NOT_EXISTS);
        }
        // 发送验证码
        smsCodeApi.sendSmsCode(AuthConvert.INSTANCE.convert(reqVO).setCreateIp(getClientIP()));
    }

    @Override
    public AuthLoginRespVO smsLogin(AuthSmsLoginReqVO reqVO) {
        // 校验验证码
        smsCodeApi.useSmsCode(AuthConvert.INSTANCE.convert(reqVO, SmsSceneEnum.ADMIN_MEMBER_LOGIN.getScene(), getClientIP()));

        // 获得用户信息
        AdminUserDO user = userService.getUserByMobile(reqVO.getMobile());
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }

        return createLoginResult(user, reqVO.getMobile(), LoginLogTypeEnum.LOGIN_MOBILE);
    }

    private void createLoginLog(Long userId, String username,
                                LoginLogTypeEnum logTypeEnum, LoginResultEnum loginResult) {
        // 插入登录日志
        LoginLogCreateReqDTO reqDTO = new LoginLogCreateReqDTO();
        reqDTO.setLogType(logTypeEnum.getType());
        reqDTO.setTraceId(TracerUtils.getTraceId());
        reqDTO.setUserId(userId);
        reqDTO.setUserType(getUserType().getValue());
        reqDTO.setUsername(username);
        reqDTO.setUserAgent(ServletUtils.getUserAgent());
        reqDTO.setUserIp(ServletUtils.getClientIP());
        reqDTO.setResult(loginResult.getResult());
        loginLogService.createLoginLog(reqDTO);
        // 更新最后登录时间
        if (userId != null && Objects.equals(LoginResultEnum.SUCCESS.getResult(), loginResult.getResult())) {
            userService.updateUserLogin(userId, ServletUtils.getClientIP());
        }
    }



    @VisibleForTesting
    void validateCaptcha(AuthLoginReqVO reqVO) {
        ResponseModel response = doValidateCaptcha(reqVO);
        // 校验验证码
        if (!response.isSuccess()) {
            // 创建登录失败日志（验证码不正确)
            createLoginLog(null, reqVO.getUsername(), LoginLogTypeEnum.LOGIN_USERNAME, LoginResultEnum.CAPTCHA_CODE_ERROR);
            throw exception(AUTH_LOGIN_CAPTCHA_CODE_ERROR, response.getRepMsg());
        }
    }

    private ResponseModel doValidateCaptcha(CaptchaVerificationReqVO reqVO) {
        // 如果验证码关闭，则不进行校验
        if (!captchaEnable) {
            return ResponseModel.success();
        }
        ValidationUtils.validate(validator, reqVO, CaptchaVerificationReqVO.CodeEnableGroup.class);
        CaptchaVO captchaVO = new CaptchaVO();
        captchaVO.setCaptchaVerification(reqVO.getCaptchaVerification());
        return captchaService.verification(captchaVO);
    }

    private AuthLoginRespVO createTokenAfterLoginSuccess(Long userId, String username, LoginLogTypeEnum logType) {
        // 插入登陆日志
        createLoginLog(userId, username, logType, LoginResultEnum.SUCCESS);
        // 创建访问令牌
        OAuth2AccessTokenDO accessTokenDO = oauth2TokenService.createAccessToken(userId, getUserType().getValue(),
                OAuth2ClientConstants.CLIENT_ID_DEFAULT, null);
        // 构建返回结果
        AuthLoginRespVO respVO = BeanUtils.toBean(accessTokenDO, AuthLoginRespVO.class);
        respVO.setLoginStatus(AuthLoginStatusEnum.SUCCESS);
        return respVO;
    }

    /**
     * 根据密码状态决定创建正式会话还是一次性改密凭证。
     */
    private AuthLoginRespVO createLoginResult(AdminUserDO user, String username, LoginLogTypeEnum logType) {
        PasswordStatusVO passwordStatus = userService.getPasswordStatus(user.getId());
        if (!Boolean.TRUE.equals(passwordStatus.getMustChange())) {
            return createTokenAfterLoginSuccess(user.getId(), username, logType);
        }

        // 预认证不是正式登录：撤销已有会话，仅允许客户端持一次性凭证完成改密。
        oauth2TokenService.removeAccessToken(user.getId(), getUserType().getValue());
        AuthLoginRespVO respVO = new AuthLoginRespVO();
        respVO.setUserId(user.getId());
        respVO.setLoginStatus(AuthLoginStatusEnum.PASSWORD_CHANGE_REQUIRED);
        respVO.setPasswordChangeToken(passwordChangeTicketRedisDAO.create(user.getId()));
        return respVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthLoginRespVO changeRequiredPassword(AuthChangeRequiredPasswordReqVO reqVO) {
        Long userId = passwordChangeTicketRedisDAO.consume(reqVO.getPasswordChangeToken());
        if (userId == null) {
            throw exception(AUTH_PASSWORD_CHANGE_TICKET_INVALID);
        }
        AdminUserDO user = userService.getUser(userId);
        if (user == null || !Boolean.TRUE.equals(userService.getPasswordStatus(userId).getMustChange())) {
            throw exception(AUTH_PASSWORD_CHANGE_TICKET_INVALID);
        }
        if (CommonStatusEnum.isDisable(user.getStatus())) {
            throw exception(AUTH_LOGIN_USER_DISABLED);
        }

        // 先更新密码并撤销历史会话，再签发全新的正式会话，避免任何旧令牌跨越改密边界。
        userService.updateRequiredPassword(userId, reqVO.getNewPassword());
        return createTokenAfterLoginSuccess(userId, user.getUsername(), LoginLogTypeEnum.LOGIN_PASSWORD_CHANGE);
    }

    @Override
    public AuthLoginRespVO refreshToken(String refreshToken) {
        OAuth2AccessTokenDO accessTokenDO = oauth2TokenService.refreshAccessToken(refreshToken, OAuth2ClientConstants.CLIENT_ID_DEFAULT);
        AuthLoginRespVO respVO = BeanUtils.toBean(accessTokenDO, AuthLoginRespVO.class);
        respVO.setLoginStatus(AuthLoginStatusEnum.SUCCESS);
        return respVO;
    }

    @Override
    public void logout(String token, Integer logType) {
        // 删除访问令牌
        OAuth2AccessTokenDO accessTokenDO = oauth2TokenService.removeAccessToken(token);
        if (accessTokenDO == null) {
            return;
        }
        // 删除成功，则记录登出日志
        createLogoutLog(accessTokenDO.getUserId(), accessTokenDO.getUserType(), logType);
    }

    private void createLogoutLog(Long userId, Integer userType, Integer logType) {
        LoginLogCreateReqDTO reqDTO = new LoginLogCreateReqDTO();
        reqDTO.setLogType(logType);
        reqDTO.setTraceId(TracerUtils.getTraceId());
        reqDTO.setUserId(userId);
        reqDTO.setUserType(userType);
        if (ObjectUtil.equal(getUserType().getValue(), userType)) {
            reqDTO.setUsername(getUsername(userId));
        } else {
            reqDTO.setUsername(memberService.getMemberUserMobile(userId));
        }
        reqDTO.setUserAgent(ServletUtils.getUserAgent());
        reqDTO.setUserIp(ServletUtils.getClientIP());
        reqDTO.setResult(LoginResultEnum.SUCCESS.getResult());
        loginLogService.createLoginLog(reqDTO);
    }

    private String getUsername(Long userId) {
        if (userId == null) {
            return null;
        }
        AdminUserDO user = userService.getUser(userId);
        return user != null ? user.getUsername() : null;
    }

    private UserTypeEnum getUserType() {
        return UserTypeEnum.ADMIN;
    }

    @Override
    public AuthLoginRespVO register(AuthRegisterReqVO registerReqVO) {
        // 1. 校验验证码
        validateCaptcha(registerReqVO);

        // 2. 校验用户名是否已存在
        Long userId = userService.registerUser(registerReqVO);

        // 3. 创建 Token 令牌，记录登录日志
        return createTokenAfterLoginSuccess(userId, registerReqVO.getUsername(), LoginLogTypeEnum.LOGIN_USERNAME);
    }

    @VisibleForTesting
    void validateCaptcha(AuthRegisterReqVO reqVO) {
        ResponseModel response = doValidateCaptcha(reqVO);
        // 验证不通过
        if (!response.isSuccess()) {
            throw exception(AUTH_REGISTER_CAPTCHA_CODE_ERROR, response.getRepMsg());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(AuthResetPasswordReqVO reqVO) {
        AdminUserDO userByMobile = userService.getUserByMobile(reqVO.getMobile());
        if (userByMobile == null) {
            throw exception(USER_MOBILE_NOT_EXISTS);
        }

        smsCodeApi.useSmsCode(new SmsCodeUseReqDTO()
                .setCode(reqVO.getCode())
                .setMobile(reqVO.getMobile())
                .setScene(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getScene())
                .setUsedIp(getClientIP())
        );

        // 用户通过短信验证码自助重置，密码本人设置，记录改密时间，不强制再次改密
        userService.resetUserPassword(userByMobile.getId(), reqVO.getPassword());
    }
}
