package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.enums.RecordChangeOperationEnum;
import com.lingan.ucp.nocode.runtime.service.live.RecordChangeCollector;
import com.lingan.ucp.nocode.web.live.RecordLiveHub;
import com.lingan.ucp.nocode.web.live.RecordLiveRelay;
import com.lingan.ucp.nocode.web.live.RecordLiveRelayConsumer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 实时推送在工具进程（正式启动类 + 懒加载 + 非 Web）里的装配：只发布、不消费，新增的 bean 与分发线程不触发工具进程自检失败。
 *
 * <p>第二个用例真的往 Redis 发布并订阅收回，只在显式打开时运行（环境变量 RECORD_LIVE_REDIS=1），不进常规门禁；用随机频道名， 不与任何环境的转发频道相同。
 */
class RecordLiveToolProcessTest {
    private static final String LIVE_DATABASE = "os-newserver0916";

    /** 与 ObjectRuleMigrationToolStartupTest 相同：运行库的 act_id_property 为空时 Flowable IDM 引擎拒绝启动。 */
    private static final String IDM_VERSION =
            "INSERT INTO public.act_id_property(name_, value_, rev_)"
                    + " SELECT 'schema.version', value_, 1 FROM public.act_ge_property"
                    + " WHERE name_ = 'schema.version' AND NOT EXISTS"
                    + " (SELECT 1 FROM public.act_id_property WHERE name_ = 'schema.version')";

    @Test
    void toolProcessWiresPublisherOnlyAndStartsNoLiveThreadBeforeTheFirstChange() {
        prepareDatabase();
        try (var context = ToolProcessGuard.start(ObjectRuleMigrationTool.servicesApplication())) {
            var factory = context.getBeanFactory();
            assertThat(factory.getBeanNamesForType(RecordLiveRelayConsumer.class, false, false))
                    .as("工具进程不是 Web 应用：没有转发消费端")
                    .isEmpty();
            assertThat(factory.getBeanNamesForType(RecordLiveHub.class, false, false))
                    .as("分发器在完整装配里（被登记簿首次交付时才创建）")
                    .hasSize(1);
            assertThat(factory.getBeanNamesForType(RecordLiveRelay.class, false, false))
                    .as("发布端在完整装配里")
                    .hasSize(1);
            assertThat(factory.getBeanNamesForType(RecordChangeCollector.class, false, false))
                    .hasSize(1);
            assertThat(threadNames()).as("没有变更之前不创建分发线程").doesNotContain("nocode-live");
        }
    }

    @Test
    void toolProcessPublishesCommittedChangesAndStillPassesTheBackgroundThreadCheck()
            throws Exception {
        Assumptions.assumeTrue(
                "1".equals(System.getenv("RECORD_LIVE_REDIS")),
                "需要真实 Redis：设置 RECORD_LIVE_REDIS=1 打开");
        prepareDatabase();
        String channel = "nocode:live:test-" + UUID.randomUUID();
        try (var context =
                ToolProcessGuard.start(
                        ObjectRuleMigrationTool.servicesApplication()
                                .properties("nocode.live.relay.channel=" + channel))) {
            var received = new LinkedBlockingQueue<String>();
            var container = new RedisMessageListenerContainer();
            container.setConnectionFactory(
                    context.getBean(StringRedisTemplate.class).getRequiredConnectionFactory());
            container.addMessageListener(
                    (message, pattern) ->
                            received.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                    new ChannelTopic(channel));
            container.afterPropertiesSet();
            container.start();
            try {
                RecordChangeCollector collector = context.getBean(RecordChangeCollector.class);
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                        .executeWithoutResult(
                                status ->
                                        collector.changed(
                                                "3057", "9001", RecordChangeOperationEnum.CREATE));
                String message = received.poll(30, TimeUnit.SECONDS);
                assertThat(message).as("工具进程提交的变更经 Redis 频道发布").isNotNull();
                JsonNode root = new ObjectMapper().readTree(message);
                RecordLiveRelay relay = context.getBean(RecordLiveRelay.class);
                assertThat(relay.channel()).as("配置的频道名整体覆盖").isEqualTo(channel);
                assertThat(root.path("v").asInt()).isEqualTo(1);
                assertThat(root.path("node").asText()).isEqualTo(relay.node());
                assertThat(root.path("origin").isNull()).as("工具进程没有请求，发起人为空").isTrue();
                assertThat(root.path("objects").size()).isEqualTo(1);
                assertThat(root.path("objects").path(0).path("objectId").asText())
                        .isEqualTo("3057");
                assertThat(root.path("objects").path(0).path("created").path(0).asText())
                        .isEqualTo("9001");
            } finally {
                container.stop();
                container.destroy();
            }
            assertThat(context.getBeanNamesForType(RecordLiveRelayConsumer.class)).isEmpty();
            assertThat(threadNames()).as("对照：分发线程此时确实在跑").contains("nocode-live");
            assertThat(ToolProcessGuard.backgroundThreads()).as("分发线程不属于工具进程禁止的后台作业线程").isEmpty();
        }
    }

    private static void prepareDatabase() {
        var url = System.getenv("SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_URL");
        assertThat(url == null ? "" : url).doesNotContain(LIVE_DATABASE);
        try (var database = NocodeToolContext.application(NocodeToolDatabase.class).run()) {
            database.getBean(JdbcTemplate.class).update(IDM_VERSION);
        }
    }

    private static List<String> threadNames() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .toList();
    }
}
