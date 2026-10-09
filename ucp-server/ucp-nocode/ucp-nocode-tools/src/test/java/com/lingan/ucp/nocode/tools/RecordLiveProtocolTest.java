package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.RecordLiveStubs.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lingan.ucp.framework.websocket.core.handler.JsonWebSocketMessageHandler;
import com.lingan.ucp.nocode.api.ApplicationAuthorization;
import com.lingan.ucp.nocode.web.live.RecordLiveHub;
import com.lingan.ucp.nocode.web.live.RecordLiveTopicListener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 端到端协议（任务书 U15）：真实的 JsonWebSocketMessageHandler 加假连接，发订阅命令帧、触发变更、收事件帧， 事件帧与契约 2.3 逐字段比对（黄金
 * JSON；eventId、timestamp、epoch 只核对存在与类型）。
 *
 * <p>本类打印的 FRAME_SAMPLE 行是真实线上帧，前端一路据此核对解析。
 */
class RecordLiveProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOPIC = "nocode.records.11.3057";

    private final AtomicReference<String> origin = new AtomicReference<>();
    private RecordLiveHub hub;
    private JsonWebSocketMessageHandler handler;
    private List<ApplicationAuthorization.ObjectGrant> grants;

    @BeforeEach
    void setup() {
        hub = new RecordLiveHub(true, null, origin::get, false);
        grants = List.of(readAll("3057"));
        handler = handler(hub);
    }

    private JsonWebSocketMessageHandler handler(RecordLiveHub target) {
        return new JsonWebSocketMessageHandler(
                List.of(
                        new RecordLiveTopicListener(
                                target,
                                (application, object, actor) -> grants,
                                Schedulers.immediate())));
    }

    @Test
    void u15_subscribeThenChangeThenUnsubscribeOverTheRealProtocolHandler() throws Exception {
        Connection connection = Connection.admin(7);
        send(handler, connection, command(1, "subscribe", TOPIC));
        String reply = connection.next();
        sample("subscribe-reply", reply);
        assertThat(JSON.readTree(reply))
                .isEqualTo(
                        JSON.readTree(
                                """
{"frame":"reply","id":1,"ok":true,
 "reply":{"type":"subscribe","topic":"nocode.records.11.3057","recovered":false}}
"""));

        origin.set("0b9c6e0e-5d0b-4e8a-9a51-3f6a7c2d1e44");
        hub.committed(batch(created("3057", "9001")));
        hub.committed(batch(updated("3057", "88", "89")));
        hub.pump();
        String event = connection.next();
        sample("records.changed kind=ids", event);
        ObjectNode frame = (ObjectNode) JSON.readTree(event);
        assertThat(frame.path("eventId").asText()).matches("[0-9a-f-]{36}");
        assertThat(frame.path("timestamp").isIntegralNumber()).isTrue();
        assertThat(frame.path("data").path("epoch").asText()).isEqualTo(hub.epoch());
        frame.remove(List.of("eventId", "timestamp"));
        ((ObjectNode) frame.path("data")).remove("epoch");
        assertThat(frame)
                .as("事件帧形状与契约 2.3 逐字段一致：不多一个字段，不少一个字段")
                .isEqualTo(
                        JSON.readTree(
                                """
{"frame":"event","topic":"nocode.records.11.3057","event":"records.changed","replay":false,
 "data":{"objectId":"3057","kind":"ids","many":false,
         "created":["9001"],"updated":["88","89"],"deleted":[],
         "origin":"0b9c6e0e-5d0b-4e8a-9a51-3f6a7c2d1e44","fromSeq":1,"seq":2}}
"""));

        // 没有发起人、量大：origin 为 null（键仍在），三个列表为空。
        origin.set(null);
        hub.committed(batch(many("3057")));
        hub.pump();
        String bulk = connection.next();
        sample("records.changed kind=object many=true", bulk);
        ObjectNode bulkFrame = (ObjectNode) JSON.readTree(bulk);
        ((ObjectNode) bulkFrame.path("data")).remove("epoch");
        assertThat(bulkFrame.path("data"))
                .isEqualTo(
                        JSON.readTree(
                                """
{"objectId":"3057","kind":"object","many":true,
 "created":[],"updated":[],"deleted":[],"origin":null,"fromSeq":3,"seq":3}
"""));
        assertThat(bulkFrame.path("data").has("origin")).as("origin 键总是输出").isTrue();

        send(handler, connection, command(2, "unsubscribe", TOPIC));
        String unsubscribed = connection.next();
        sample("unsubscribe-reply", unsubscribed);
        assertThat(JSON.readTree(unsubscribed).path("ok").asBoolean()).isTrue();
        assertThat(hub.count(connection.session)).as("退订后从分发器注销").isZero();
        hub.committed(batch(updated("3057", "88")));
        hub.pump();
        assertThat(connection.sent).as("退订后不再收到事件").isEmpty();
    }

    @Test
    void subscriptionRepliesUseTheExistingProtocolErrorCodes() throws Exception {
        Connection anonymous = Connection.anonymous();
        send(handler, anonymous, command(1, "subscribe", TOPIC));
        String unauthorized = anonymous.next();
        sample("subscribe-rejected not-logged-in", unauthorized);
        assertError(unauthorized, "UNAUTHORIZED", "实时更新需要登录");

        grants = List.of(grant("3057", "ALL", Set.of("UPDATE"), Map.of()));
        Connection stranger = Connection.admin(8);
        send(handler, stranger, command(2, "subscribe", TOPIC));
        String forbidden = stranger.next();
        sample("subscribe-rejected no-read-grant", forbidden);
        assertError(forbidden, "UNAUTHORIZED", "没有此对象的查看权限");

        send(handler, stranger, command(3, "subscribe", "nocode.records.011.3057"));
        String unknown = stranger.next();
        sample("subscribe-rejected malformed-topic", unknown);
        assertThat(JSON.readTree(unknown).path("error").path("code").asText())
                .as("格式不符的主题没有监听器认领")
                .isEqualTo("TOPIC_NOT_FOUND");

        grants = List.of(readAll("3057"));
        Connection busy = Connection.admin(9);
        for (int index = 1; index <= RecordLiveHub.MAX_SUBSCRIPTIONS; index++) {
            send(handler, busy, command(index, "subscribe", "nocode.records.11." + index));
            assertThat(JSON.readTree(busy.next()).path("ok").asBoolean()).isTrue();
        }
        send(handler, busy, command(99, "subscribe", "nocode.records.11.51"));
        String limited = busy.next();
        sample("subscribe-rejected limit", limited);
        assertError(limited, "INVALID_COMMAND", "订阅数超过上限");

        Connection closed = Connection.admin(10);
        send(
                handler(new RecordLiveHub(false, null, () -> null, false)),
                closed,
                command(1, "subscribe", TOPIC));
        String disabled = closed.next();
        sample("subscribe-rejected disabled", disabled);
        assertError(disabled, "INVALID_COMMAND", "实时更新已关闭");
    }

    @Test
    void closingTheConnectionReleasesItsSubscriptions() throws Exception {
        Connection connection = Connection.admin(7);
        send(handler, connection, command(1, "subscribe", TOPIC));
        connection.next();
        assertThat(hub.count(connection.session)).isEqualTo(1);
        handler.afterConnectionClosed(connection.session, CloseStatus.NORMAL);
        assertThat(hub.count(connection.session)).as("连接关闭后订阅从分发器注销").isZero();
    }

    private static void assertError(String reply, String code, String message) throws Exception {
        JsonNode node = JSON.readTree(reply);
        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("error").path("code").asText()).isEqualTo(code);
        assertThat(node.path("error").path("message").asText()).isEqualTo(message);
    }

    private static void send(
            JsonWebSocketMessageHandler target, Connection connection, String payload)
            throws Exception {
        target.handleMessage(connection.session, new TextMessage(payload));
    }

    private static String command(int id, String type, String topic) {
        return "{\"frame\":\"command\",\"id\":"
                + id
                + ",\"command\":{\"type\":\""
                + type
                + "\",\"topic\":\""
                + topic
                + "\"}}";
    }

    private static void sample(String label, String frame) {
        System.out.println("FRAME_SAMPLE [" + label + "] " + frame);
    }
}
