package com.richuang.os.module.system.framework.security.config;

import com.richuang.os.module.system.framework.security.core.interceptor.PasswordExpireInterceptor;
import com.richuang.os.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 密码策略的 Web 配置：注册密码过期拦截器
 *
 * @author os
 */
@Configuration(proxyBeanMethods = false)
public class PasswordPolicyWebConfiguration implements WebMvcConfigurer {

    @Resource
    private AdminUserService userService;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PasswordExpireInterceptor(userService))
                .addPathPatterns("/admin-api/**");
    }

}
