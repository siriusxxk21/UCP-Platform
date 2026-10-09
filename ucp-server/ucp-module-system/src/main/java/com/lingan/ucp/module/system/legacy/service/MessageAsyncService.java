package com.lingan.ucp.module.system.legacy.service;

import java.util.List;

/**
 * 消息异步处理服务
 */
public interface MessageAsyncService {

    /**
     * 异步批量插入接收记录并更新未读统计
     *
     * @param messageId   消息ID
     * @param receiverIds 接收人ID列表
     * @param tenantId    租户ID
     * @param messageType 消息类型
     */
    void asyncInsertReceivers(String messageId, List<String> receiverIds, String tenantId, Integer messageType);
}
