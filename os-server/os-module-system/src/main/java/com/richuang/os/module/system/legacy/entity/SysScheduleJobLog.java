package com.richuang.os.module.system.legacy.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 定时任务执行日志实体类
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_schedule_job_log")
public class SysScheduleJobLog extends BaseEntity {

    /**
     * 租户ID
     */
    private String tenantId;

    /**
     * 场景ID
     */
    private String sceneId;

    /**
     * 场景名称
     */
    private String sceneName;

    /**
     * 任务类型: SCENE_TRIGGER/MANUAL
     */
    private String jobType;

    /**
     * 触发类型: CRON/MANUAL/EVENT
     */
    private String triggerType;

    /**
     * 执行时间
     */
    private LocalDateTime executeTime;

    /**
     * 执行耗时（毫秒）
     */
    private Integer duration;

    /**
     * 执行状态: 0-失败 1-成功 2-部分成功
     */
    private Integer status;

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
    private String variableSnapshot;
}
