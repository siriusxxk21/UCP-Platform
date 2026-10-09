package com.lingan.ucp.module.system.legacy.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingan.ucp.module.system.legacy.dto.MessageSceneDTO;
import com.lingan.ucp.module.system.legacy.dto.MessageSceneQueryDTO;
import com.lingan.ucp.module.system.legacy.vo.MessageSceneVO;
import com.lingan.ucp.module.system.legacy.vo.ScheduleJobLogVO;

import java.util.List;
import java.util.Map;

/**
 * 业务场景服务接口
 */
public interface MessageSceneService {

    /**
     * 分页查询场景列表
     */
    Page<MessageSceneVO> list(MessageSceneQueryDTO queryDTO);

    /**
     * 获取场景详情
     */
    MessageSceneVO getDetail(String id);

    /**
     * 根据编码获取场景
     */
    MessageSceneVO getByCode(String sceneCode);

    /**
     * 创建场景
     */
    String create(MessageSceneDTO dto);

    /**
     * 更新场景
     */
    void update(MessageSceneDTO dto);

    /**
     * 删除场景
     */
    void delete(String id);

    /**
     * 批量删除场景
     */
    void batchDelete(List<String> ids);

    /**
     * 启用场景
     */
    void enable(String id);

    /**
     * 禁用场景
     */
    void disable(String id);

    /**
     * 手动执行场景
     */
    String executeManually(String id, Map<String, Object> variables);

    /**
     * 获取场景执行日志
     */
    Page<ScheduleJobLogVO> listLogs(String sceneId, Integer pageNum, Integer pageSize);

    /**
     * 获取待执行的场景列表（定时任务调度用）
     */
    List<MessageSceneVO> listPendingScenes();

    /**
     * 更新场景执行统计
     */
    void updateExecuteStats(String sceneId);

    /**
     * 获取场景所有接收人
     */
    List<String> resolveReceivers(String sceneId, Map<String, Object> variables);
}
