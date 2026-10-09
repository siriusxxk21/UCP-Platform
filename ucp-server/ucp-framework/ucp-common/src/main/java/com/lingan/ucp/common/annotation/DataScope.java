package com.lingan.ucp.common.annotation;

import java.lang.annotation.*;

/**
 * 数据权限注解
 * 用于标记需要数据权限控制的方法
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataScope {

    /**
     * 部门表别名
     * 用于拼接SQL时的表别名，支持多表（逗号分隔）
     * 例如："u" 或 "u,d"（表示同时限制user表和department表）
     */
    String deptAlias() default "";

    /**
     * 用户表别名
     * 用于拼接SQL时的表别名，支持多表（逗号分隔）
     * 例如："u" 或 "u,o"（表示同时限制user表和order表）
     */
    String userAlias() default "";

    /**
     * 是否忽略数据权限
     * 设置为true时，不进行数据权限过滤
     */
    boolean ignore() default false;
}
