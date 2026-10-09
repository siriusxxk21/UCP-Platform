package com.richuang.os.module.system.legacy.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 消息模板DTO
 */
@Data
public class MessageTemplateDTO {

    private String id;

    /**
     * 模板编码
     */
    @NotBlank(message = "模板编码不能为空")
    private String templateCode;

    /**
     * 模板名称
     */
    @NotBlank(message = "模板名称不能为空")
    private String templateName;

    /**
     * 模板类型: 1-普通消息 2-告警通知 3-审批提醒 4-数据报告 5-自定义
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
     * 内容结构（JSON）
     */
    private Object contentStructure;

    /**
     * 变量定义
     */
    private List<VariableDef> variables;

    /**
     * 样式配置
     */
    private Object styleConfig;

    /**
     * 状态: 0-草稿 1-已发布 2-已下架
     */
    private Integer status;

    /**
     * 变量定义
     */
    @Data
    public static class VariableDef {
        private String name;
        private String label;
        private String type;
        private Boolean required;
        private Object defaultValue;
        private DataSourceConfig dataSource;
    }

    /**
     * 数据源配置
     */
    @Data
    public static class DataSourceConfig {
        private String type;
        private String datasetCode;
        private String apiUrl;
    }
}
