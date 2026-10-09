package com.lingan.ucp.framework.websocket.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.TypeUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.framework.common.util.json.JsonUtils;
import com.lingan.ucp.framework.websocket.core.listener.WebSocketEventMessage;
import com.lingan.ucp.framework.websocket.core.listener.WebSocketMessageListener;
import com.lingan.ucp.framework.websocket.core.listener.WebSocketSubscription;
import com.lingan.ucp.framework.websocket.core.message.JsonWebSocketMessage;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.Disposable;
import reactor.core.Disposables;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Command / Reply / Event WebSocket 协议处理器。
 *
 * <p>支持单个对象和对象数组帧。命令 ID 只关联 Reply，服务端业务消息按 Topic 和 Event 路由；
 * 每个 Session 只能持有同一 Topic 的一个物理订阅。</p>
 */
@Slf4j
public class JsonWebSocketMessageHandler extends TextWebSocketHandler {

    private static final String COMMAND_CONNECT = "connect";
    private static final String COMMAND_SUBSCRIBE = "subscribe";
    private static final String COMMAND_UNSUBSCRIBE = "unsubscribe";
    private static final String COMMAND_PRESENCE = "presence";

    private final List<WebSocketMessageListener<Object>> listeners = new ArrayList<>();
    private final Map<WebSocketSession, Map<String, Disposable>> sessionSubscriptions = new java.util.concurrent.ConcurrentHashMap<>();

    @SuppressWarnings({"rawtypes", "unchecked"})
    public JsonWebSocketMessageHandler(List<? extends WebSocketMessageListener> listenerList) {
        listenerList.forEach(listener -> listeners.add((WebSocketMessageListener<Object>) listener));
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) throws IOException {
        if (message.getPayloadLength() == 0) {
            return;
        }
        if ("ping".equals(message.getPayload())) {
            sendText(session, "pong");
            return;
        }

        try {
            JsonNode root = JsonUtils.parseTree(message.getPayload());
            boolean batch = root.isArray();
            List<JsonNode> commandNodes = commandNodes(root);
            Map<String, Disposable> subscriptions = sessionSubscriptions.computeIfAbsent(
                    session, ignored -> new java.util.concurrent.ConcurrentHashMap<>());
            Set<String> reservedTopics = new HashSet<>(subscriptions.keySet());
            List<PreparedCommand> preparedCommands = commandNodes.stream()
                    .map(node -> prepareCommand(session, node, subscriptions, reservedTopics))
                    .toList();

            sendProtocolFrames(session, preparedCommands.stream().map(PreparedCommand::reply).toList(), batch);
            preparedCommands.forEach(PreparedCommand::activate);
        } catch (Exception exception) {
            log.warn("WebSocket 协议帧处理失败: sessionId={}, error={}",
                    session.getId(), exception.getMessage());
            sendProtocolFrames(session, List.of(failure(null, "INVALID_COMMAND",
                    "Malformed WebSocket command frame", false)), false);
        }
    }

    private List<JsonNode> commandNodes(JsonNode root) {
        if (root.isArray()) {
            List<JsonNode> nodes = new ArrayList<>();
            root.forEach(nodes::add);
            return nodes;
        }
        return List.of(root);
    }

    private PreparedCommand prepareCommand(WebSocketSession session,
                                           JsonNode node,
                                           Map<String, Disposable> subscriptions,
                                           Set<String> reservedTopics) {
        JsonWebSocketMessage.CommandFrame frame;
        try {
            frame = JsonUtils.parseObject(node.toString(), JsonWebSocketMessage.CommandFrame.class);
        } catch (RuntimeException exception) {
            return PreparedCommand.replyOnly(failure(readCommandId(node), "INVALID_COMMAND",
                    "Command frame cannot be parsed", false));
        }
        if (frame == null || frame.id() == null || !JsonWebSocketMessage.FRAME_COMMAND.equals(frame.frame())
                || frame.command() == null
                || StrUtil.isBlank(frame.command().type())) {
            return PreparedCommand.replyOnly(failure(frame == null ? null : frame.id(), "INVALID_COMMAND",
                    "frame=command, id and command.type are required", false));
        }

        return switch (frame.command().type()) {
            case COMMAND_CONNECT -> prepareConnect(frame, subscriptions);
            case COMMAND_SUBSCRIBE -> prepareSubscribe(session, frame, subscriptions, reservedTopics);
            case COMMAND_UNSUBSCRIBE -> prepareUnsubscribe(frame, subscriptions, reservedTopics);
            case COMMAND_PRESENCE -> preparePresence(frame);
            default -> PreparedCommand.replyOnly(failure(frame.id(), "INVALID_COMMAND",
                    "Unsupported command type: " + frame.command().type(), false));
        };
    }

    private PreparedCommand prepareConnect(JsonWebSocketMessage.CommandFrame frame,
                                           Map<String, Disposable> subscriptions) {
        Map<String, String> activeSubscriptions = new LinkedHashMap<>();
        subscriptions.keySet().forEach(topic -> activeSubscriptions.put(topic, "subscribed"));
        JsonWebSocketMessage.Reply reply = new JsonWebSocketMessage.Reply(
                COMMAND_CONNECT, null, null, null, null, activeSubscriptions);
        return PreparedCommand.replyOnly(JsonWebSocketMessage.ReplyFrame.success(frame.id(), reply));
    }

    private PreparedCommand prepareSubscribe(WebSocketSession session,
                                             JsonWebSocketMessage.CommandFrame frame,
                                             Map<String, Disposable> subscriptions,
                                             Set<String> reservedTopics) {
        String topic = frame.command().topic();
        if (StrUtil.isBlank(topic)) {
            return PreparedCommand.replyOnly(failure(frame.id(), "INVALID_COMMAND",
                    "Subscribe command requires topic", false));
        }
        if (!reservedTopics.add(topic)) {
            return PreparedCommand.replyOnly(failure(frame.id(), "INVALID_COMMAND",
                    "Topic is already subscribed", false));
        }

        WebSocketMessageListener<Object> listener = findListener(topic);
        if (listener == null) {
            reservedTopics.remove(topic);
            return PreparedCommand.replyOnly(failure(frame.id(), "TOPIC_NOT_FOUND",
                    "No listener for topic", false));
        }
        try {
            Object data = convertCommandData(listener, frame.command().data());
            WebSocketSubscription subscription = listener.subscribe(
                    session, topic, frame.command().offset(), data);
            JsonWebSocketMessage.Reply reply = new JsonWebSocketMessage.Reply(
                    COMMAND_SUBSCRIBE, topic, subscription.recovered(),
                    subscription.headOffset(), null, null);
            Runnable activation = () -> activateSubscription(session, topic, subscription, subscriptions);
            return new PreparedCommand(JsonWebSocketMessage.ReplyFrame.success(frame.id(), reply), activation);
        } catch (Exception exception) {
            reservedTopics.remove(topic);
            String code = exception instanceof SecurityException ? "UNAUTHORIZED" : "INVALID_COMMAND";
            log.warn("WebSocket Topic 订阅被拒绝: sessionId={}, topic={}, error={}",
                    session.getId(), topic, exception.getMessage());
            return PreparedCommand.replyOnly(failure(frame.id(), code, safeMessage(exception), false));
        }
    }

    private PreparedCommand prepareUnsubscribe(JsonWebSocketMessage.CommandFrame frame,
                                               Map<String, Disposable> subscriptions,
                                               Set<String> reservedTopics) {
        String topic = frame.command().topic();
        if (StrUtil.isBlank(topic)) {
            return PreparedCommand.replyOnly(failure(frame.id(), "INVALID_COMMAND",
                    "Unsubscribe command requires topic", false));
        }
        Disposable disposable = subscriptions.remove(topic);
        if (disposable != null) {
            disposable.dispose();
        }
        reservedTopics.remove(topic);
        return PreparedCommand.replyOnly(JsonWebSocketMessage.ReplyFrame.success(
                frame.id(), JsonWebSocketMessage.Reply.simple(COMMAND_UNSUBSCRIBE, topic)));
    }

    private PreparedCommand preparePresence(JsonWebSocketMessage.CommandFrame frame) {
        String state = frame.command().state();
        if (!"foreground".equals(state) && !"background".equals(state)) {
            return PreparedCommand.replyOnly(failure(frame.id(), "INVALID_COMMAND",
                    "Presence state must be foreground or background", false));
        }
        JsonWebSocketMessage.Reply reply = new JsonWebSocketMessage.Reply(
                COMMAND_PRESENCE, null, null, null, state, null);
        return PreparedCommand.replyOnly(JsonWebSocketMessage.ReplyFrame.success(frame.id(), reply));
    }

    private void activateSubscription(WebSocketSession session,
                                      String topic,
                                      WebSocketSubscription subscription,
                                      Map<String, Disposable> subscriptions) {
        Disposable.Swap holder = Disposables.swap();
        subscriptions.put(topic, holder);
        Disposable actual = subscription.events()
                .doFinally(signal -> subscriptions.remove(topic, holder))
                .subscribe(event -> sendEvent(session, topic, event),
                        error -> sendSubscriptionError(session, topic, error));
        holder.replace(actual);
    }

    private void sendEvent(WebSocketSession session, String topic, WebSocketEventMessage event) {
        String eventId = StrUtil.blankToDefault(event.eventId(), UUID.randomUUID().toString());
        Long timestamp = event.timestamp() == null ? System.currentTimeMillis() : event.timestamp();
        JsonWebSocketMessage.EventFrame frame = new JsonWebSocketMessage.EventFrame(
                eventId, topic, event.event(), event.offset(), timestamp, event.replay(), event.data());
        sendProtocolFrames(session, List.of(frame), false);
    }

    private void sendSubscriptionError(WebSocketSession session, String topic, Throwable error) {
        log.warn("WebSocket Topic 事件流异常: sessionId={}, topic={}, error={}",
                session.getId(), topic, error.getMessage());
        Map<String, Object> data = Map.of(
                "code", "INTERNAL_ERROR",
                "message", "Subscription stream terminated",
                "snapshotRequired", true);
        WebSocketEventMessage event = WebSocketEventMessage.now("protocol.subscription.error", data);
        sendEvent(session, topic, event);
    }

    private WebSocketMessageListener<Object> findListener(String topic) {
        return listeners.stream().filter(listener -> listener.supportsTopic(topic)).findFirst().orElse(null);
    }

    private Object convertCommandData(WebSocketMessageListener<Object> listener, Object data) {
        if (data == null) {
            return null;
        }
        Type dataType = TypeUtil.getTypeArgument(listener.getClass(), 0);
        return JsonUtils.parseObject(JsonUtils.toJsonString(data), dataType);
    }

    private JsonWebSocketMessage.ReplyFrame failure(Object id, String code, String message,
                                                    boolean snapshotRequired) {
        return JsonWebSocketMessage.ReplyFrame.failure(id,
                new JsonWebSocketMessage.ProtocolError(code, message, snapshotRequired));
    }

    private Object readCommandId(JsonNode node) {
        JsonNode id = node == null ? null : node.get("id");
        if (id == null || id.isNull()) {
            return null;
        }
        if (id.isIntegralNumber()) {
            return id.numberValue();
        }
        return id.asText();
    }

    private String safeMessage(Exception exception) {
        return StrUtil.blankToDefault(exception.getMessage(), "Subscription rejected");
    }

    private void sendProtocolFrames(WebSocketSession session,
                                    List<? extends JsonWebSocketMessage> frames,
                                    boolean batch) {
        if (!session.isOpen() || frames.isEmpty()) {
            return;
        }
        Object payload = batch ? frames : frames.get(0);
        sendText(session, JsonUtils.toJsonString(payload));
    }

    private void sendText(WebSocketSession session, String payload) {
        if (!session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException exception) {
            log.warn("WebSocket 消息发送失败: sessionId={}, error={}",
                    session.getId(), exception.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session,
                                      @NonNull CloseStatus status) throws Exception {
        Map<String, Disposable> subscriptions = sessionSubscriptions.remove(session);
        if (subscriptions != null) {
            subscriptions.values().forEach(Disposable::dispose);
        }
        super.afterConnectionClosed(session, status);
    }

    /**
     * 命令回复和回复发送后的订阅激活动作。
     */
    private record PreparedCommand(JsonWebSocketMessage.ReplyFrame reply, Runnable activation) {

        private static PreparedCommand replyOnly(JsonWebSocketMessage.ReplyFrame reply) {
            return new PreparedCommand(reply, () -> {
            });
        }

        private void activate() {
            activation.run();
        }
    }
}
