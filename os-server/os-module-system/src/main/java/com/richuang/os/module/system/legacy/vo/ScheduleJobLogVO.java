package com.richuang.os.module.system.legacy.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 定时任务执行日志VO
 */
@Data
public class ScheduleJobLogVO {

    private String id;

    /**
     * 场景ID
     */
    private String sceneId;

    /**
     * 场景名称
     */
    private String sceneName;

    /**
     * 任务类型
     */
    private String jobType;

    /**
     * 触发类型
     */
    private String triggerType;

    /**
     * 执行时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime executeTime;

    /**
     * 执行耗时（毫秒）
     */
    private Integer duration;

    /**
     * 执行状态
     */
    private Integer status;

    /**
     * 状态描述
     */
    private String statusDesc;

    /**
     * 生成的消息ID
     */
    private String messageId;

    /**
     * 接收人数量
     */
    private Integer receiverCount;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 变量值快照
     */
    private Map<String, Object> variableSnapshot;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
}
