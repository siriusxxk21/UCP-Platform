package com.richuang.os.module.system.legacy.service;

import com.richuang.os.module.system.legacy.dto.MessageDTO;
import com.richuang.os.module.system.legacy.dto.MessageQueryDTO;
import com.richuang.os.module.system.legacy.vo.MessageDetailVO;
import com.richuang.os.module.system.legacy.vo.MessagePageVO;
import com.richuang.os.module.system.legacy.vo.UnreadCountVO;

import java.util.List;
import java.util.Map;

/**
 * 消息服务接口
 */
public interface MessageService {

    /**
     * 获取用户消息列表
     */
    MessagePageVO list(MessageQueryDTO queryDTO, String userId);

    /**
     * 获取消息详情
     */
    MessageDetailVO getDetail(String messageId, String userId);

    /**
     * 发送消息
     */
    void send(MessageDTO messageDTO, String senderId, String tenantId);

    /**
     * 标记消息已读
     */
    void markAsRead(String messageId, String userId);

    /**
     * 批量标记已读
     */
    void batchMarkAsRead(List<String> messageIds, String userId);

    /**
     * 标记全部已读
     */
    void markAllAsRead(String userId, String tenantId);

    /**
     * 标星/取消标星
     */
    void toggleStar(String messageId, String userId);

    /**
     * 删除消息(移入回收站)
     */
    void delete(String messageId, String userId);

    /**
     * 批量删除消息
     */
    void batchDelete(List<String> messageIds, String userId);

    /**
     * 批量级联删除消息（管理端使用）
     * 删除消息及其所有关联数据（接收记录、附件关联等）
     */
    void batchDeleteCascade(List<String> messageIds, String tenantId);

    /**
     * 恢复消息
     */
    void restore(String messageId, String userId);

    /**
     * 彻底删除消息
     */
    void deletePermanently(String messageId, String userId);

    /**
     * 获取未读消息数
     */
    UnreadCountVO getUnreadCount(String userId, String tenantId);

    /**
     * 发送草稿
     */
    void saveDraft(MessageDTO messageDTO, String senderId, String tenantId);

    /**
     * 获取发送的消息列表(发送记录)
     */
    MessagePageVO listSent(MessageQueryDTO queryDTO, String senderId, String tenantId);

    /**
     * 创建并发送消息（场景执行使用）
     *
     * @param tenantId         租户ID
     * @param templateId       模板ID
     * @param sceneId          场景ID
     * @param title            消息标题
     * @param contentStructure 结构化内容
     * @param variables        变量值
     * @param receiverIds      接收人ID列表
     * @param messageType      消息类型
     * @param priority         优先级
     * @return 消息ID
     */
    String createAndSend(String tenantId, String templateId, String sceneId, String title,
                         Object contentStructure, Map<String, Object> variables,
                         List<String> receiverIds, Integer messageType, Integer priority);
}
