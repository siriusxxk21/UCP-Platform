package com.richuang.os.module.system.legacy.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 业务场景VO
 */
@Data
public class MessageSceneVO {

    private String id;

    /**
     * 场景编码
     */
    private String sceneCode;

    /**
     * 场景名称
     */
    private String sceneName;

    /**
     * 场景类型
     */
    private Integer sceneType;

    /**
     * 场景分类
     */
    private String category;

    /**
     * 场景描述
     */
    private String description;

    /**
     * 关联模板ID
     */
    private String templateId;

    /**
     * 关联模板名称
     */
    private String templateName;

    /**
     * 触发条件配置
     */
    private Object triggerConditions;

    /**
     * 推送规则配置
     */
    private Object pushRules;

    /**
     * 调度配置
     */
    private Object scheduleConfig;

    /**
     * 接收人配置
     */
    private Object receiverConfig;

    /**
     * 变量值配置
     */
    private Map<String, Object> variableValues;

    /**
     * 状态
     */
    private Integer status;

    /**
     * 最后执行时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime lastExecuteTime;

    /**
     * 下次执行时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime nextExecuteTime;

    /**
     * 执行次数
     */
    private Integer executeCount;

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
     * Cron表达式描述（中文）
     */
    private String cronDescription;
}
