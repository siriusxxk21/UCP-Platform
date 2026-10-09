package com.lingan.ucp.module.system.legacy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 业务场景DTO
 */
@Data
public class MessageSceneDTO {

    private String id;

    /**
     * 场景编码
     */
    @NotBlank(message = "场景编码不能为空")
    private String sceneCode;

    /**
     * 场景名称
     */
    @NotBlank(message = "场景名称不能为空")
    private String sceneName;

    /**
     * 场景类型: 1-定时推送 2-事件触发 3-手动触发
     */
    @NotNull(message = "场景类型不能为空")
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
    private Long templateId;

    /**
     * 触发条件配置
     */
    private Object triggerConditions;

    /**
     * 推送规则配置
     */
    private PushRules pushRules;

    /**
     * 调度配置
     */
    private ScheduleConfig scheduleConfig;

    /**
     * 接收人配置
     */
    private ReceiverConfig receiverConfig;

    /**
     * 变量值配置
     */
    private Map<String, Object> variableValues;

    /**
     * 状态: 0-禁用 1-启用
     */
    private Integer status;

    /**
     * 推送规则
     */
    @Data
    public static class PushRules {
        private Integer priority;
        private Integer messageType;
        private DeduplicationConfig deduplication;
        private ThrottleConfig throttle;
    }

    /**
     * 去重配置
     */
    @Data
    public static class DeduplicationConfig {
        private Boolean enabled;
        private Integer windowMinutes;
    }

    /**
     * 限流配置
     */
    @Data
    public static class ThrottleConfig {
        private Boolean enabled;
        private Integer maxPerHour;
    }

    /**
     * 调度配置
     */
    @Data
    public static class ScheduleConfig {
        private String cronExpression;
        private String timezone;
        private Boolean enabled;
        private String misfirePolicy;
    }

    /**
     * 接收人配置
     */
    @Data
    public static class ReceiverConfig {
        private Integer sendType;
        private List<TargetInfo> targets;
        private List<ConditionReceiver> conditionReceivers;
    }

    /**
     * 目标信息
     */
    @Data
    public static class TargetInfo {
        private String type;
        private String id;
        private String name;
    }

    /**
     * 条件接收人
     */
    @Data
    public static class ConditionReceiver {
        private String condition;
        private List<TargetInfo> targets;
    }
}
