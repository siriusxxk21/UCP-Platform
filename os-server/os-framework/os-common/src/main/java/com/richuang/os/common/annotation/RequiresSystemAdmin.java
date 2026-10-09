package com.richuang.os.common.annotation;

import java.lang.annotation.*;

/**
 * 标注在 Controller 方法上，表示该接口仅限系统管理员(userType=3)访问
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresSystemAdmin {
}
