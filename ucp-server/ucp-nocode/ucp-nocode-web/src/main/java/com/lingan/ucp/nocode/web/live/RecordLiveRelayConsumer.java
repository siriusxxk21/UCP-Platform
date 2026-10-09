package com.lingan.ucp.nocode.web.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * 转发的消费端：只在服务进程（Servlet Web 应用）里装配。订阅转发频道，把别的进程发来的变更交给本进程的分发器；自己发的忽略。
 *
 * <p>监听容器由本类自己持有、自己启停，不作为容器里的 bean，不影响底座按类型装配的消息监听容器。启动失败只记日志：实时推送的 跨进程部分不可用，不拦应用启动。
 */
public class RecordLiveRelayConsumer implements MessageListener, InitializingBean, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(RecordLiveRelayConsumer.class);

    private final RecordLiveHub hub;
    private final RecordLiveRelay relay;
    private final RedisConnectionFactory connections;
    private RedisMessageListenerContainer container;

    public RecordLiveRelayConsumer(
            RecordLiveHub hub, RecordLiveRelay relay, RedisConnectionFactory connections) {
        this.hub = hub;
        this.relay = relay;
        this.connections = connections;
    }

    @Override
    public void afterPropertiesSet() {
        if (!hub.enabled() || relay.channel() == null || connections == null) return;
        try {
            RedisMessageListenerContainer created = new RedisMessageListenerContainer();
            created.setBeanName("nocode-live-relay");
            created.setConnectionFactory(connections);
            created.addMessageListener(this, new ChannelTopic(relay.channel()));
            created.afterPropertiesSet();
            created.start();
            container = created;
            log.info("记录变更转发消费端已订阅频道 {}", relay.channel());
        } catch (Throwable error) {
            log.warn("记录变更转发消费端启动失败，别的进程提交的变更不会推给本进程的订阅: {}", error.toString());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        handle(new String(message.getBody(), StandardCharsets.UTF_8));
    }

    /** 处理一条转发消息：解析、丢弃自己发的与损坏的，其余交给分发器。从不抛出。 */
    public void handle(String payload) {
        RecordLiveRelay.Incoming incoming = relay.decode(payload);
        if (incoming != null) hub.accept(incoming.batch(), incoming.origin());
    }

    @Override
    public void destroy() {
        RedisMessageListenerContainer running = container;
        container = null;
        if (running == null) return;
        try {
            running.stop();
            running.destroy();
        } catch (Throwable error) {
            log.warn("记录变更转发消费端关闭出错，已忽略: {}", error.toString());
        }
    }
}
