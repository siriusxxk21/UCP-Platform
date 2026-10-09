package com.lingan.ucp.module.system.legacy.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingan.ucp.module.system.legacy.vo.ScheduleJobLogVO;

/**
 * 定时任务执行日志服务接口
 */
public interface ScheduleJobLogService {

    /**
     * 分页查询日志列表
     */
    Page<ScheduleJobLogVO> list(String tenantId, String sceneId, Integer pageNum, Integer pageSize);

    /**
     * 获取日志详情
     */
    ScheduleJobLogVO getDetail(String id);

    /**
     * 创建日志
     */
    String createLog(String sceneId, String sceneName, String jobType, String triggerType,
                     Object variableSnapshot);

    /**
     * 更新日志执行结果
     */
    void updateResult(String logId, Integer status, String messageId, Integer receiverCount,
                      String errorMessage, Integer duration);
}
