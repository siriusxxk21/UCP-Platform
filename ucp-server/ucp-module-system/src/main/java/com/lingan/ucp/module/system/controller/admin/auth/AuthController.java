package com.lingan.ucp.module.system.controller.admin.auth;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.lingan.ucp.framework.common.enums.CommonStatusEnum;
import com.lingan.ucp.framework.common.enums.UserTypeEnum;
import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.framework.datapermission.core.annotation.DataPermission;
import com.lingan.ucp.framework.security.config.SecurityProperties;
import com.lingan.ucp.framework.security.core.util.SecurityFrameworkUtils;
import com.lingan.ucp.module.system.controller.admin.auth.vo.*;
import com.lingan.ucp.module.system.controller.admin.user.vo.profile.PasswordStatusVO;
import com.lingan.ucp.module.system.convert.auth.AuthConvert;
import com.lingan.ucp.module.system.dal.dataobject.permission.MenuDO;
import com.lingan.ucp.module.system.dal.dataobject.permission.RoleDO;
import com.lingan.ucp.module.system.dal.dataobject.user.AdminUserDO;
import com.lingan.ucp.module.system.enums.logger.LoginLogTypeEnum;
import com.lingan.ucp.module.system.service.auth.AdminAuthService;
import com.lingan.ucp.module.system.service.permission.MenuService;
import com.lingan.ucp.module.system.service.permission.PermissionService;
import com.lingan.ucp.module.system.service.permission.RoleService;
import com.lingan.ucp.module.system.service.user.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.lingan.ucp.framework.common.pojo.Result.success;
import static com.lingan.ucp.framework.common.util.collection.CollectionUtils.convertSet;
import static com.lingan.ucp.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "管理后台 - 认证")
@RestController
@RequestMapping("/system/auth")
@Validated
@Slf4j
public class AuthController {

    @Resource
    private AdminAuthService authService;
    @Resource
    private AdminUserService userService;
    @Resource
    private RoleService roleService;
    @Resource
    private MenuService menuService;
    @Resource
    private PermissionService permissionService;

    @Resource
    private SecurityProperties securityProperties;

    @PostMapping("/login")
    @PermitAll
    @Operation(summary = "使用账号密码登录")
    public Result<AuthLoginRespVO> login(@RequestBody @Valid AuthLoginReqVO reqVO) {
        return success(authService.login(reqVO));
    }

    @PutMapping("/change-required-password")
    @PermitAll
    @Operation(summary = "使用一次性预认证凭证完成强制改密")
    public Result<AuthLoginRespVO> changeRequiredPassword(
            @RequestBody @Valid AuthChangeRequiredPasswordReqVO reqVO) {
        return success(authService.changeRequiredPassword(reqVO));
    }

    @PostMapping("/logout")
    @PermitAll
    @Operation(summary = "登出系统")
    public Result<Boolean> logout(HttpServletRequest request) {
        String token = SecurityFrameworkUtils.obtainAuthorization(request,
                securityProperties.getTokenHeader(), securityProperties.getTokenParameter());
        if (StrUtil.isNotBlank(token)) {
            authService.logout(token, LoginLogTypeEnum.LOGOUT_SELF.getType());
        }
        return success(true);
    }

    @PostMapping("/refresh-token")
    @PermitAll
    @Operation(summary = "刷新令牌")
    @Parameter(name = "refreshToken", description = "刷新令牌", required = true)
    public Result<AuthLoginRespVO> refreshToken(@RequestParam("refreshToken") String refreshToken) {
        return success(authService.refreshToken(refreshToken));
    }

    @GetMapping("/get-permission-info")
    @Operation(summary = "获取登录用户的权限信息")
    @DataPermission(enable = false) // 忽略数据权限，避免因为过滤，导致无法查询用户。类似：https://t.zsxq.com/LHnrp
    public Result<AuthPermissionInfoRespVO> getPermissionInfo() {
        // 1.1 获得用户信息
        AdminUserDO user = userService.getUser(getLoginUserId());
        if (user == null) {
            return success(null);
        }

        // 1.2 获得角色列表
        Set<Long> roleIds = permissionService.getUserRoleIdListByUserId(getLoginUserId());
        AuthPermissionInfoRespVO respVO;
        if (CollUtil.isEmpty(roleIds)) {
            respVO = AuthConvert.INSTANCE.convert(user, Collections.emptyList(), Collections.emptyList());
        } else {
            List<RoleDO> roles = roleService.getRoleList(roleIds);
            roles.removeIf(role -> !CommonStatusEnum.ENABLE.getStatus().equals(role.getStatus())); // 移除禁用的角色

            // 1.3 获得菜单列表
            Set<Long> menuIds = permissionService.getRoleMenuListByRoleId(convertSet(roles, RoleDO::getId));
            List<MenuDO> menuList = menuService.getMenuList(menuIds);
            menuList = menuService.filterDisableMenus(menuList);
            respVO = AuthConvert.INSTANCE.convert(user, roles, menuList);
        }

        // 2. 填充密码状态（初始密码/过期强制改密、临期提醒）
        PasswordStatusVO passwordStatus = userService.getPasswordStatus(user.getId());
        respVO.getUser().setMustChangePassword(passwordStatus.getMustChange());
        respVO.getUser().setPasswordRemainDays(passwordStatus.getRemainDays());
        respVO.getUser().setPasswordRemindDays(passwordStatus.getRemindDays());
        return success(respVO);
    }

    @PostMapping("/register")
    @PermitAll
    @Operation(summary = "注册用户")
    public Result<AuthLoginRespVO> register(@RequestBody @Valid AuthRegisterReqVO registerReqVO) {
        return success(authService.register(registerReqVO));
    }

    // ========== 短信登录相关 ==========

    @PostMapping("/sms-login")
    @PermitAll
    @Operation(summary = "使用短信验证码登录")
    // 可按需开启限流：https://github.com/YunaiV/ruoyi-vue-pro/issues/851
    // @RateLimiter(time = 60, count = 6, keyResolver = ExpressionRateLimiterKeyResolver.class, keyArg = "#reqVO.mobile")
    public Result<AuthLoginRespVO> smsLogin(@RequestBody @Valid AuthSmsLoginReqVO reqVO) {
        return success(authService.smsLogin(reqVO));
    }

    @PostMapping("/send-sms-code")
    @PermitAll
    @Operation(summary = "发送手机验证码")
    public Result<Boolean> sendLoginSmsCode(@RequestBody @Valid AuthSmsSendReqVO reqVO) {
        authService.sendSmsCode(reqVO);
        return success(true);
    }

    @PostMapping("/reset-password")
    @PermitAll
    @Operation(summary = "重置密码")
    public Result<Boolean> resetPassword(@RequestBody @Valid AuthResetPasswordReqVO reqVO) {
        authService.resetPassword(reqVO);
        return success(true);
    }

}
