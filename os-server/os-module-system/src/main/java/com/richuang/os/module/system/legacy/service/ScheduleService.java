package com.richuang.os.module.system.legacy.service;

/**
 * 定时调度服务接口
 */
public interface ScheduleService {

    /**
     * 创建定时任务
     *
     * @param sceneId        场景ID
     * @param cronExpression Cron表达式
     * @param tenantId       租户ID
     */
    void createJob(String sceneId, String cronExpression, String tenantId);

    /**
     * 更新定时任务
     *
     * @param sceneId        场景ID
     * @param cronExpression Cron表达式
     * @param tenantId       租户ID
     */
    void updateJob(String sceneId, String cronExpression, String tenantId);

    /**
     * 删除定时任务
     *
     * @param sceneId 场景ID
     */
    void deleteJob(String sceneId);

    /**
     * 暂停定时任务
     *
     * @param sceneId 场景ID
     */
    void pauseJob(String sceneId);

    /**
     * 恢复定时任务
     *
     * @param sceneId 场景ID
     */
    void resumeJob(String sceneId);

    /**
     * 立即触发执行
     *
     * @param sceneId  场景ID
     * @param tenantId 租户ID
     */
    void triggerJob(String sceneId, String tenantId);

    /**
     * 执行场景
     *
     * @param sceneId     场景ID
     * @param tenantId    租户ID
     * @param triggerType 触发类型
     * @return 生成的消息ID
     */
    String executeScene(String sceneId, String tenantId, String triggerType);

    /**
     * 检查任务是否存在
     *
     * @param sceneId 场景ID
     * @return 是否存在
     */
    boolean checkExists(String sceneId);
}
