package com.lingan.ucp.nocode.web.live;

import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实时推送的装配与两个开关：nocode.live.enabled（默认开；关掉后不分发、不转发，订阅一律回「实时更新已关闭」）与
 * nocode.live.relay.enabled（默认开；关掉后不发布、不消费，本进程内分发不受影响）。nocode.live.relay.channel 非空时整体覆盖转发频道名。
 */
@Configuration(proxyBeanMethods = false)
public class RecordLiveConfiguration {
    private static final Logger log = LoggerFactory.getLogger(RecordLiveConfiguration.class);

    /** 同时在发送的连接数上限；线程按需创建、空闲 60 秒回收，没有订阅时一个线程都不占。 */
    static final int SENDER_THREADS = 32;

    @Bean(destroyMethod = "close")
    public RecordLiveHub recordLiveHub(
            @Value("${nocode.live.enabled:true}") boolean enabled,
            ObjectProvider<RecordLiveRelay> relay) {
        return new RecordLiveHub(enabled, enabled ? relay.getIfAvailable() : null);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "nocode.live.relay",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public RecordLiveRelay recordLiveRelay(
            StringRedisTemplate redis,
            ObjectProvider<JdbcTemplate> jdbc,
            @Value("${nocode.live.relay.channel:}") String configured) {
        String channel =
                RecordLiveRelay.channelName(
                        configured,
                        () -> {
                            JdbcTemplate template = jdbc.getIfAvailable();
                            if (template == null) return null;
                            try {
                                return template.queryForObject(
                                        "select current_database()", String.class);
                            } catch (RuntimeException error) {
                                log.warn("读取数据库标识失败: {}", error.toString());
                                return null;
                            }
                        });
        if (channel == null) log.warn("取不到数据库标识且未配置 nocode.live.relay.channel，记录变更不做跨进程转发");
        return new RecordLiveRelay(redis, channel);
    }

    /** 消费端只在服务进程里装配；导入工具等非 Web 进程只发布。 */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(
            prefix = "nocode.live.relay",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public RecordLiveRelayConsumer recordLiveRelayConsumer(
            RecordLiveHub hub, RecordLiveRelay relay, StringRedisTemplate redis) {
        return new RecordLiveRelayConsumer(hub, relay, redis.getConnectionFactory());
    }

    @Bean
    public Senders recordLiveSenders() {
        return new Senders();
    }

    /** 授权策略在订阅时才解析：实时推送的装配不决定策略的创建时机，策略不可用时订阅按无权处理。 */
    @Bean
    public RecordLiveTopicListener recordLiveTopicListener(
            RecordLiveHub hub, ObjectProvider<ApplicationRuntimePolicy> policy, Senders senders) {
        return new RecordLiveTopicListener(
                hub,
                (application, object, actor) ->
                        policy.getObject().scopeGrants(application, object, actor),
                senders.scheduler());
    }

    /** 发送线程：协议处理器在消费事件流的线程上对连接做阻塞写，这里与分发线程分开，一个写不动的连接只占住一个发送线程。 */
    public static final class Senders implements DisposableBean {
        private final ThreadPoolExecutor executor;
        private final Scheduler scheduler;

        public Senders() {
            AtomicInteger serial = new AtomicInteger();
            executor =
                    new ThreadPoolExecutor(
                            SENDER_THREADS,
                            SENDER_THREADS,
                            60,
                            TimeUnit.SECONDS,
                            new LinkedBlockingQueue<>(),
                            task -> {
                                Thread thread =
                                        new Thread(
                                                task,
                                                "nocode-live-send-" + serial.incrementAndGet());
                                thread.setDaemon(true);
                                return thread;
                            });
            executor.allowCoreThreadTimeOut(true);
            scheduler = Schedulers.fromExecutorService(executor, "nocode-live-send");
        }

        public Scheduler scheduler() {
            return scheduler;
        }

        @Override
        public void destroy() {
            scheduler.dispose();
            executor.shutdownNow();
        }
    }
}
