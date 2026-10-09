package com.lingan.ucp.module.system.framework.security.core.interceptor;

import com.lingan.ucp.framework.security.core.util.SecurityFrameworkUtils;
import com.lingan.ucp.module.system.controller.admin.user.vo.profile.PasswordStatusVO;
import com.lingan.ucp.module.system.service.user.AdminUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

import static com.lingan.ucp.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.lingan.ucp.module.system.enums.ErrorCodeConstants.AUTH_PASSWORD_EXPIRED;

/**
 * 密码过期拦截器
 *
 * 当用户处于强制改密状态（初始密码未修改，或密码已超过有效期）时，
 * 除白名单接口外一律返回 {@link com.lingan.ucp.module.system.enums.ErrorCodeConstants#AUTH_PASSWORD_EXPIRED}，
 * 防止前端绕过强制改密直接调用业务接口。
 *
 * @author os
 */
@RequiredArgsConstructor
public class PasswordExpireInterceptor implements HandlerInterceptor {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * 白名单：强制改密状态下仍允许访问的接口（获取权限信息、登出、刷新令牌、修改密码本身）
     */
    private static final List<String> WHITELIST = List.of(
            "**/system/auth/get-permission-info",
            "**/system/auth/logout",
            "**/system/auth/refresh-token",
            "**/system/user/profile/update-password"
    );

    private final AdminUserService userService;

    @Override
    @SuppressWarnings("NullableProblems")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 未登录（如 PermitAll 接口）直接放行，由正常认证流程处理
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            return true;
        }
        // 2. 白名单接口放行
        if (isWhitelist(request.getRequestURI())) {
            return true;
        }
        // 3. 非强制改密状态放行（密码状态带本地缓存，不会每请求查库）
        PasswordStatusVO status = userService.getPasswordStatus(userId);
        if (!Boolean.TRUE.equals(status.getMustChange())) {
            return true;
        }
        throw exception(AUTH_PASSWORD_EXPIRED);
    }

    private boolean isWhitelist(String uri) {
        for (String pattern : WHITELIST) {
            if (PATH_MATCHER.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

}
