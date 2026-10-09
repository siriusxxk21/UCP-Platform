package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.RecordLiveStubs.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.richuang.os.framework.websocket.core.handler.JsonWebSocketMessageHandler;
import com.richuang.os.nocode.runtime.service.live.RecordChangeBatch;
import com.richuang.os.nocode.runtime.service.live.RecordChangeListener;
import com.richuang.os.nocode.web.live.RecordLiveConfiguration;
import com.richuang.os.nocode.web.live.RecordLiveHub;
import com.richuang.os.nocode.web.live.RecordLiveRelay;
import com.richuang.os.nocode.web.live.RecordLiveRelayConsumer;
import com.richuang.os.nocode.web.live.RecordLiveTopicListener;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.TextMessage;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨进程转发与两个开关（契约 6.6、6.7、第 10 章；任务书 X1–X8）。Redis 模板用替身，不连 Redis、不连库；装配用 Spring Boot 的上下文运行器分别按「非 Web
 * 应用」与「Servlet Web 应用」起一遍。
 */
class RecordLiveRelayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CHANNEL = "nocode:live:fixture_db";
    private static final String LEDGER = "3057";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    void x1_publishedMessageMatchesTheContractShapeFieldByField() throws Exception {
        RecordLiveRelay relay = new RecordLiveRelay(redis, CHANNEL);
        relay.publish(
                batch(
                        created(LEDGER, "9001"),
                        new RecordChangeBatch.ObjectChange(
                                "4001", true, List.of(), List.of(), List.of())),
                "0b9c6e0e-5d0b-4e8a-9a51-3f6a7c2d1e44");
        String sent = sentTo(CHANNEL, 1);
        System.out.println("RELAY_SAMPLE " + sent);
        ObjectNode message = (ObjectNode) JSON.readTree(sent);
        assertThat(message.path("node").asText()).isEqualTo(relay.node()).isNotBlank();
        message.remove("node");
        assertThat(message)
                .isEqualTo(
                        JSON.readTree(
                                """
{"v":1,"origin":"0b9c6e0e-5d0b-4e8a-9a51-3f6a7c2d1e44",
 "objects":[
   {"objectId":"3057","many":false,"created":["9001"],"updated":[],"deleted":[]},
   {"objectId":"4001","many":true,"created":[],"updated":[],"deleted":[]}]}
"""));
        // 没有发起人：origin 键仍在，值为 null。
        clearInvocations(redis);
        relay.publish(batch(updated(LEDGER, "88")), null);
        JsonNode anonymous = JSON.readTree(sentTo(CHANNEL, 1));
        assertThat(anonymous.has("origin")).isTrue();
        assertThat(anonymous.path("origin").isNull()).isTrue();
    }

    @Test
    void x2_ownMessagesAreIgnoredAndOtherNodesAreDispatchedToLocalSubscribers() {
        RecordLiveRelay relay = new RecordLiveRelay(redis, CHANNEL);
        RecordLiveRelay otherProcess = new RecordLiveRelay(redis, CHANNEL);
        RecordLiveHub hub = new RecordLiveHub(true, relay, () -> null, false);
        RecordLiveRelayConsumer consumer = new RecordLiveRelayConsumer(hub, relay, null);
        Sink viewer = new Sink();
        hub.subscribe(new Object(), LEDGER, true, viewer);

        consumer.handle(relay.encode(batch(created(LEDGER, "9001")), null));
        hub.pump();
        assertThat(viewer.frames).as("自己发的转发消息不再分发一遍").isEmpty();

        consumer.handle(
                otherProcess.encode(
                        batch(created(LEDGER, "9002")), "0b9c6e0e-5d0b-4e8a-9a51-3f6a7c2d1e44"));
        hub.pump();
        assertThat(viewer.frames).as("别的进程发来的分发给本地订阅").hasSize(1);
        assertThat(viewer.frames.getFirst().created()).containsExactly("9002");
        assertThat(viewer.frames.getFirst().origin())
                .isEqualTo("0b9c6e0e-5d0b-4e8a-9a51-3f6a7c2d1e44");
        verify(redis, never()).convertAndSend(anyString(), any());
    }

    @Test
    void x3_publishFailureIsInvisibleToTheCommittingThreadAndLocalDeliveryContinues() {
        AtomicLong now = new AtomicLong(1_000_000);
        RecordLiveRelay relay = new RecordLiveRelay(redis, CHANNEL, now::get);
        doThrow(new IllegalStateException("Redis 不通"))
                .when(redis)
                .convertAndSend(anyString(), any());
        RecordLiveHub hub = new RecordLiveHub(true, relay, () -> null, false);
        Sink viewer = new Sink();
        hub.subscribe(new Object(), LEDGER, true, viewer);
        assertThatCode(
                        () -> {
                            hub.committed(batch(updated(LEDGER, "88")));
                            hub.pump();
                        })
                .doesNotThrowAnyException();
        assertThat(viewer.frames).as("转发失败不影响本地订阅").hasSize(1);
        // 失败后短暂停发：Redis 不通时不让每个批都去等一次超时；过了停发期再试，不补发失败的那几条。
        hub.committed(batch(updated(LEDGER, "89")));
        hub.pump();
        verify(redis, times(1)).convertAndSend(anyString(), any());
        assertThat(viewer.frames).hasSize(2);
        now.addAndGet(5_001);
        doReturn(1L).when(redis).convertAndSend(anyString(), any());
        hub.committed(batch(updated(LEDGER, "90")));
        hub.pump();
        verify(redis, times(2)).convertAndSend(anyString(), any());
        assertThat(viewer.frames).hasSize(3);
    }

    @Test
    void x4_channelIsPrefixPlusDatabaseUnlessConfigured() {
        assertThat(RecordLiveRelay.channelName("", () -> "os-newserver0916"))
                .isEqualTo("nocode:live:os-newserver0916");
        assertThat(RecordLiveRelay.channelName(null, () -> "nocode_it_run"))
                .isEqualTo("nocode:live:nocode_it_run");
        assertThat(RecordLiveRelay.channelName("  ", () -> "nocode_it_run"))
                .isEqualTo("nocode:live:nocode_it_run");
        assertThat(
                        RecordLiveRelay.channelName(
                                "custom:channel",
                                () -> {
                                    throw new AssertionError("配置了频道名就不去查数据库");
                                }))
                .as("配置值整体覆盖")
                .isEqualTo("custom:channel");
        assertThat(RecordLiveRelay.channelName("", () -> null)).as("取不到数据库标识 ⇒ 不转发").isNull();
        // 没有频道时发布是空操作。
        new RecordLiveRelay(redis, null).publish(batch(updated(LEDGER, "88")), null);
        verify(redis, never()).convertAndSend(anyString(), any());
    }

    @Test
    void x5_unknownVersionOrBrokenPayloadIsDroppedWithoutThrowing() throws Exception {
        RecordLiveRelay relay = new RecordLiveRelay(redis, CHANNEL);
        RecordLiveRelay otherProcess = new RecordLiveRelay(redis, CHANNEL);
        RecordLiveHub hub = new RecordLiveHub(true, relay, () -> null, false);
        RecordLiveRelayConsumer consumer = new RecordLiveRelayConsumer(hub, relay, null);
        Sink viewer = new Sink();
        hub.subscribe(new Object(), LEDGER, true, viewer);
        String valid = otherProcess.encode(batch(created(LEDGER, "9001")), null);
        ObjectNode future = (ObjectNode) JSON.readTree(valid);
        future.put("v", 2);
        for (String payload :
                List.of(
                        future.toString(),
                        "{\"v\":1,\"node\":\"x\",\"objects\":",
                        "not json at all",
                        "",
                        "[]",
                        "{\"v\":1,\"node\":\"x\",\"objects\":[{\"objectId\":\"../etc\"}]}",
                        "{\"node\":\"x\",\"objects\":[]}")) {
            assertThatCode(() -> consumer.handle(payload))
                    .as("损坏的消息不抛出：%s", payload)
                    .doesNotThrowAnyException();
        }
        hub.pump();
        assertThat(viewer.frames).as("版本不认识或损坏的消息一律丢弃").isEmpty();
        // 对照：同一条消息版本正确时会被分发。
        consumer.handle(valid);
        hub.pump();
        assertThat(viewer.frames).hasSize(1);
        // 标识列表形状不对：不逐条列出，按整体刷新处理，不丢。
        consumer.handle(
                "{\"v\":1,\"node\":\"other\",\"origin\":null,\"objects\":[{\"objectId\":\"3057\","
                        + "\"many\":false,\"created\":\"oops\",\"updated\":[],\"deleted\":[]}]}");
        hub.pump();
        assertThat(viewer.frames.getLast().kind()).isEqualTo("object");
    }

    @Test
    void x6_nonWebProcessPublishesButHasNoConsumer() {
        context()
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(RecordLiveRelayConsumer.class);
                            assertThat(context).hasSingleBean(RecordLiveRelay.class);
                            RecordLiveRelay relay = context.getBean(RecordLiveRelay.class);
                            assertThat(relay.channel()).isEqualTo(CHANNEL);
                            // 登记簿经容器拿到的监听者就是分发器；提交后经 Redis 模板发布。
                            RecordChangeListener listener =
                                    context.getBean(RecordChangeListener.class);
                            assertThat(listener).isSameAs(context.getBean(RecordLiveHub.class));
                            listener.committed(batch(created(LEDGER, "9001")));
                            // 容器关闭时分发器把队列里剩余的处理完并完成转发（工具进程最后一批靠它）。
                            context.getBean(RecordLiveHub.class).close();
                            assertThat(JSON.readTree(sentTo(CHANNEL, 1)).path("objects").size())
                                    .isEqualTo(1);
                        });
        webContext()
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context)
                                    .as("对照：Servlet Web 应用里有消费端")
                                    .hasSingleBean(RecordLiveRelayConsumer.class);
                        });
    }

    @Test
    void x7_liveDisabledRejectsSubscriptionsAndNeitherDispatchesNorPublishes() {
        webContext()
                .withPropertyValues("nocode.live.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            RecordLiveHub hub = context.getBean(RecordLiveHub.class);
                            assertThat(hub.enabled()).isFalse();
                            Sink viewer = new Sink();
                            hub.subscribe(new Object(), LEDGER, true, viewer);
                            // 保存照常：登记簿照样调用监听者，分发器直接返回。
                            assertThatCode(
                                            () ->
                                                    context.getBean(RecordChangeListener.class)
                                                            .committed(
                                                                    batch(created(LEDGER, "9001"))))
                                    .doesNotThrowAnyException();
                            hub.close();
                            assertThat(viewer.frames).as("关闭后不分发").isEmpty();
                            verify(redis, never()).convertAndSend(anyString(), any());
                            Connection connection = Connection.admin(7);
                            new JsonWebSocketMessageHandler(
                                            List.of(context.getBean(RecordLiveTopicListener.class)))
                                    .handleMessage(
                                            connection.session,
                                            new TextMessage(
                                                    "{\"frame\":\"command\",\"id\":1,\"command\":"
                                                            + "{\"type\":\"subscribe\",\"topic\":"
                                                            + "\"nocode.records.11.3057\"}}"));
                            JsonNode reply = JSON.readTree(connection.next());
                            assertThat(reply.path("ok").asBoolean()).isFalse();
                            assertThat(reply.path("error").path("code").asText())
                                    .isEqualTo("INVALID_COMMAND");
                            assertThat(reply.path("error").path("message").asText())
                                    .isEqualTo("实时更新已关闭");
                        });
    }

    @Test
    void x8_relayDisabledKeepsLocalDispatchWithoutPublishingOrConsuming() {
        webContext()
                .withPropertyValues("nocode.live.relay.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(RecordLiveRelay.class);
                            assertThat(context).doesNotHaveBean(RecordLiveRelayConsumer.class);
                            RecordLiveHub hub = context.getBean(RecordLiveHub.class);
                            assertThat(hub.enabled()).isTrue();
                            Sink viewer = new Sink();
                            hub.subscribe(new Object(), LEDGER, true, viewer);
                            hub.committed(batch(created(LEDGER, "9001")));
                            hub.close();
                            assertThat(viewer.frames).as("本进程内分发照常").hasSize(1);
                            verify(redis, never()).convertAndSend(anyString(), any());
                        });
        // 配置的频道名整体覆盖数据库标识。
        context()
                .withPropertyValues("nocode.live.relay.channel=custom:channel")
                .run(
                        context ->
                                assertThat(context.getBean(RecordLiveRelay.class).channel())
                                        .isEqualTo("custom:channel"));
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withUserConfiguration(RecordLiveConfiguration.class)
                .withBean(StringRedisTemplate.class, () -> redis)
                .withBean(JdbcTemplate.class, RecordLiveRelayTest::database);
    }

    private WebApplicationContextRunner webContext() {
        return new WebApplicationContextRunner()
                .withUserConfiguration(RecordLiveConfiguration.class)
                .withBean(StringRedisTemplate.class, () -> redis)
                .withBean(JdbcTemplate.class, RecordLiveRelayTest::database);
    }

    private static JdbcTemplate database() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select current_database()", String.class))
                .thenReturn("fixture_db");
        return jdbc;
    }

    /** 替身模板在这个频道上收到的最后一条消息；同时核对总共发布了几次。 */
    private String sentTo(String channel, int times) {
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(redis, times(times)).convertAndSend(eq(channel), captor.capture());
        return captor.getValue().toString();
    }
}
