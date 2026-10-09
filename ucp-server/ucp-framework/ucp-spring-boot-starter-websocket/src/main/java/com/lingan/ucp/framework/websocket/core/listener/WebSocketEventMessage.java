package com.lingan.ucp.framework.websocket.core.listener;

import java.util.UUID;

/**
 * Listener 输出的领域事件，不包含由 Handler 注入的 Topic。
 *
 * @param eventId   全局去重标识
 * @param event     领域事件名称
 * @param offset    Topic 内恢复位置
 * @param timestamp 服务端事件时间
 * @param replay    是否为恢复补发
 * @param data      领域数据
 */
public record WebSocketEventMessage(
        String eventId,
        String event,
        String offset,
        Long timestamp,
        boolean replay,
        Object data
) {

    /**
     * 创建不参与 Offset 恢复的即时事件。
     */
    public static WebSocketEventMessage now(String event, Object data) {
        return new WebSocketEventMessage(
                UUID.randomUUID().toString(), event, null, System.currentTimeMillis(), false, data);
    }
}
