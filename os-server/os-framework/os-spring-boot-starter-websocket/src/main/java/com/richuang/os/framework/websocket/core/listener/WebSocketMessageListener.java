package com.richuang.os.framework.websocket.core.listener;

import org.springframework.web.socket.WebSocketSession;

/**
 * WebSocket Topic 监听器。
 *
 * <p>实现类负责 Topic 权限和业务参数校验，并返回可恢复的领域事件流；物理连接、命令回复和
 * Event 外层帧由 WebSocket Handler 统一处理。</p>
 *
 * @param <T> subscribe command.data 的业务参数类型
 */
public interface WebSocketMessageListener<T> {

    /**
     * 建立 Topic 订阅。
     *
     * @param session 当前连接
     * @param topic   订阅 Topic
     * @param offset  客户端最后成功处理的位置
     * @param data    业务订阅参数
     * @return 事件流和恢复结果
     */
    WebSocketSubscription subscribe(WebSocketSession session, String topic, String offset, T data);

    /**
     * 返回实现类负责的 Topic 或 Topic 前缀。
     */
    String getTopic();

    /**
     * 默认支持精确 Topic；动态资源 Topic 可按业务边界重写。
     */
    default boolean supportsTopic(String topic) {
        return getTopic().equals(topic);
    }
}
