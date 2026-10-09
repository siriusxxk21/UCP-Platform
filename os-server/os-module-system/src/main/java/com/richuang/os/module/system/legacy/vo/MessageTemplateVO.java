package com.richuang.os.module.system.legacy.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息模板VO
 */
@Data
public class MessageTemplateVO {

    private String id;

    /**
     * 模板编码
     */
    private String templateCode;

    /**
     * 模板名称
     */
    private String templateName;

    /**
     * 模板类型
     */
    private Integer templateType;

    /**
     * 模板分类
     */
    private String category;

    /**
     * 模板描述
     */
    private String description;

    /**
     * 内容结构
     */
    private Object contentStructure;

    /**
     * 变量定义
     */
    private Object variables;

    /**
     * 样式配置
     */
    private Object styleConfig;

    /**
     * 状态
     */
    private Integer status;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 使用次数
     */
    private Integer useCount;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;

    /**
     * 创建人姓名
     */
    private String createByName;

    /**
     * 组件列表（用于前端渲染）
     */
    private List<ComponentInfo> components;

    /**
     * 组件信息
     */
    @Data
    public static class ComponentInfo {
        private String id;
        private String type;
        private Object config;
        private Integer order;
    }
}
