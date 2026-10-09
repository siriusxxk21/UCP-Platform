package com.richuang.os.framework.websocket.core.listener;

import reactor.core.publisher.Flux;

/**
 * Topic 订阅建立结果。
 *
 * @param events     恢复事件与实时事件组成的连续事件流
 * @param recovered  是否按客户端 Offset 完成恢复
 * @param headOffset 建立订阅时的 Topic 最新位置
 */
public record WebSocketSubscription(
        Flux<WebSocketEventMessage> events,
        boolean recovered,
        String headOffset
) {
}
