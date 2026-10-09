package com.lingan.ucp.module.system.legacy.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.common.context.DataScopeContext;
import com.lingan.ucp.module.system.legacy.common.context.UserContext;
import com.lingan.ucp.common.tenant.TenantContext;
import com.lingan.ucp.common.util.JwtUtil;
import com.lingan.ucp.module.system.legacy.vo.UserInfoVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

@Slf4j
// @Component -- 已停用（v2.0 统一认证迁移），认证链路统一由 TokenAuthenticationFilter 处理
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    // Token 续期阈值：剩余时间小于 30 分钟时自动续期
    @Value("${jwt.refresh-threshold:1800000}")
    private long refreshThreshold;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行OPTIONS请求
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }

        // 优先从Header获取token，其次从URL参数获取（支持图片等资源请求）
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        } else {
            // 从URL参数获取token
            token = request.getParameter("token");
        }

        if (token == null || token.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(Result.error("请先登录")));
            return false;
        }
        if (jwtUtil.verifyToken(token) == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(Result.error("登录已过期，请重新登录")));
            return false;
        }

        String userId = jwtUtil.getUserIdFromToken(token);
        String username = jwtUtil.getUsernameFromToken(token);
        String tenantId = jwtUtil.getTenantIdFromToken(token);

        request.setAttribute("userId", userId);
        request.setAttribute("tenantId", tenantId);

        // 设置租户上下文
        if (tenantId != null && !tenantId.isEmpty()) {
            TenantContext.setTenantId(tenantId);
            log.debug("设置租户上下文: userId={}, tenantId={}", userId, tenantId);
        }

        // 从 Redis 加载完整用户信息，注入 UserContext（供 DataScopeAspect 使用）
        if (userId != null) {
            try {
                String userInfoJson = redisTemplate.opsForValue().get("userInfo:" + userId);
                if (userInfoJson != null) {
                    UserInfoVO userInfo = objectMapper.readValue(userInfoJson, UserInfoVO.class);
                    UserContext.setCurrentUser(userInfo);
                    log.debug("加载用户上下文: userId={}, dataScope={}", userId, userInfo.getDataScope());
                }
            } catch (Exception e) {
                log.warn("加载用户信息失败, userId={}", userId, e);
            }
        }

        // 检查是否需要续期 Token（滑动过期）
        if (jwtUtil.shouldRefreshToken(token, refreshThreshold)) {
            String newToken = jwtUtil.generateToken(userId, username, tenantId);
            request.setAttribute("newToken", newToken);
            log.debug("Token 已续期，用户: {}", username);

            // 同步更新 Redis 中的 Token 缓存
            try {
                redisTemplate.opsForValue().set("token:" + userId, newToken, 1, java.util.concurrent.TimeUnit.DAYS);
                // 同时续期 userInfo 缓存
                redisTemplate.expire("userInfo:" + userId, 1, java.util.concurrent.TimeUnit.DAYS);
                log.debug("Redis 缓存已同步续期, userId={}", userId);
            } catch (Exception e) {
                log.warn("Redis 缓存续期失败, userId={}", userId, e);
            }
        }

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView modelAndView) throws Exception {
        // 如果有新 Token，放入响应头
        String newToken = (String) request.getAttribute("newToken");
        if (newToken != null) {
            response.setHeader("X-New-Token", newToken);
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 请求结束后清除所有 ThreadLocal，防止线程池复用导致上下文污染
        TenantContext.clear();
        UserContext.clear();
        DataScopeContext.clear();
    }
}
