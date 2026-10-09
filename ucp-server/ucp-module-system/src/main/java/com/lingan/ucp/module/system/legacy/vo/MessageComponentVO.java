package com.lingan.ucp.module.system.legacy.vo;

import lombok.Data;

/**
 * 消息内容组件VO
 */
@Data
public class MessageComponentVO {

    private String id;

    /**
     * 组件编码
     */
    private String componentCode;

    /**
     * 组件名称
     */
    private String componentName;

    /**
     * 组件类型
     */
    private String componentType;

    /**
     * 组件配置Schema
     */
    private Object configSchema;

    /**
     * 默认配置值
     */
    private Object defaultConfig;

    /**
     * 默认样式
     */
    private Object defaultStyle;

    /**
     * 组件图标
     */
    private String icon;

    /**
     * 组件描述
     */
    private String description;

    /**
     * 数据源类型
     */
    private String dataSourceType;

    /**
     * 数据源配置
     */
    private Object dataSourceConfig;

    /**
     * 是否支持实时刷新
     */
    private Boolean supportRefresh;

    /**
     * 是否系统组件
     */
    private Boolean isSystem;

    /**
     * 排序
     */
    private Integer sortOrder;
}
