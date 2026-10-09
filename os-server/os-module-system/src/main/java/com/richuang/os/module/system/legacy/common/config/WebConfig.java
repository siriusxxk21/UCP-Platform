package com.richuang.os.module.system.legacy.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 
 * 注意：旧版 JwtInterceptor 已停用（v2.0 统一认证迁移），认证链路统一由新版 TokenAuthenticationFilter 处理。
 * CORS 已通过 CorsFilterConfig（Filter 级别）处理，不再使用 addCorsMappings。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    // 旧版 JwtInterceptor 已停用，认证统一由 TokenAuthenticationFilter（Spring Security Filter）处理
    // private final JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 旧版 JwtInterceptor 已停用（v2.0 统一认证迁移）
        // registry.addInterceptor(jwtInterceptor)
        //         .addPathPatterns("/api/**")
        //         .excludePathPatterns(
        //                 "/api/auth/login",
        //                 "/api/file/logo/**",
        //                 "/api/system/attachment/preview/**",
        //                 "/api/system/attachment/download/**"
        //         );
    }
}
