package com.lingan.ucp.common.jsonschema.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自定义 JSON Schema 注解
 *
 * <p>标注在 Java bean 类或字段上，用于补充 JSON Schema（draft-04）的元信息，
 * 由 {@code JsonSchemaGenerator} 解析并合并到生成的 schema 中。</p>
 *
 * @author os
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
public @interface Schema {

    /**
     * 标题
     */
    String title() default "";

    /**
     * 描述
     */
    String description() default "";

    /**
     * 是否为必填字段
     */
    boolean required() default false;

    /**
     * 字段默认值示例
     */
    String example() default "";

    /**
     * 是否忽略该字段（不参与 schema 生成）
     */
    boolean ignore() default false;

    /**
     * 显式指定 JSON 类型（如 string / number / integer / boolean / array / object），为空则按 Java 类型推断
     */
    String type() default "";

    /**
     * 允许的枚举值（用于 enum 类型字段）
     */
    String[] enumValues() default {};

    /**
     * 额外 JSON Schema 属性（JSON 字符串），如 {"minimum":0,"maximum":100}
     */
    String extra() default "";

}
