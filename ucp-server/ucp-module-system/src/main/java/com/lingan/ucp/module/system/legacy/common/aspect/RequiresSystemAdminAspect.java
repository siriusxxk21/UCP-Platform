package com.lingan.ucp.module.system.legacy.common.aspect;

import com.lingan.ucp.module.system.legacy.common.context.UserContext;
import com.lingan.ucp.common.exception.BusinessException;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/**
 * 系统管理员权限校验切面
 * 拦截标注了 @RequiresSystemAdmin 的方法，校验当前用户是否为系统管理员
 */
@Aspect
@Component
public class RequiresSystemAdminAspect {

    /**
     * 系统管理员类型标识
     */
    private static final int SYSTEM_ADMIN_TYPE = 3;

    @Before("@annotation(com.lingan.ucp.common.annotation.RequiresSystemAdmin)")
    public void checkSystemAdmin(JoinPoint joinPoint) {
        if (!UserContext.hasUser()) {
            throw new BusinessException("未登录或登录已过期");
        }
        Integer userType = UserContext.getCurrentUser().getUserType();
        if (userType == null || userType != SYSTEM_ADMIN_TYPE) {
            throw new BusinessException("权限不足，仅系统管理员可操作");
        }
    }
}
