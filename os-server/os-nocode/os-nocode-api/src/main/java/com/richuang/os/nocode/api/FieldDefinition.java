package com.richuang.os.nocode.api;

/**
 * 基础字段定义；所有 ID 以字符串传输，避免浏览器整数精度丢失。
 *
 * @param key 新字段使用临时 key；已保存字段与 id 相同
 * @param id 字段稳定 ID；新字段为空，由服务端生成
 * @param type B1 允许的基础类型枚举值
 * @param length 仅单行文本使用，其他类型必须为空
 * @param precision 小数总位数，仅 DECIMAL 使用
 * @param scale 小数位数，不能超过 precision
 * @param required 业务约束定义，结构发布后才生效
 * @param unique 唯一约束定义，草稿保存不创建业务索引
 * @param sort 当前草稿中的展示顺序
 */
public record FieldDefinition(
        String key,
        String id,
        String code,
        String name,
        String type,
        Integer length,
        Integer precision,
        Integer scale,
        Boolean required,
        Boolean unique,
        Integer sort) {}
