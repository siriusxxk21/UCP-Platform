package com.richuang.os.framework.websocket.core.message;

import java.io.Serializable;
import java.util.Map;

/**
 * WebSocket 外层协议帧。
 *
 * <p>协议使用 frame 区分客户端控制命令、命令回复和服务端业务事件。业务数据只允许出现在
 * command.data 或 event.data 中，避免 type 同时承担协议类型、Topic 和领域事件三种语义。</p>
 */
public sealed interface JsonWebSocketMessage extends Serializable
        permits JsonWebSocketMessage.CommandFrame,
        JsonWebSocketMessage.ReplyFrame,
        JsonWebSocketMessage.EventFrame {

    String FRAME_COMMAND = "command";
    String FRAME_REPLY = "reply";
    String FRAME_EVENT = "event";

    /**
     * 返回当前帧的协议类型。
     */
    String frame();

    /**
     * 客户端控制命令帧，id 只用于关联本次命令回复。
     */
    record CommandFrame(String frame, Object id, Command command) implements JsonWebSocketMessage {

        public CommandFrame(Object id, Command command) {
            this(FRAME_COMMAND, id, command);
        }
    }

    /**
     * 客户端命令内容。
     */
    record Command(String type, String topic, String offset, String state, Object data) implements Serializable {
    }

    /**
     * 服务端命令回复帧。
     */
    record ReplyFrame(String frame, Object id, boolean ok, Reply reply,
                      ProtocolError error) implements JsonWebSocketMessage {

        public static ReplyFrame success(Object id, Reply reply) {
            return new ReplyFrame(FRAME_REPLY, id, true, reply, null);
        }

        public static ReplyFrame failure(Object id, ProtocolError error) {
            return new ReplyFrame(FRAME_REPLY, id, false, null, error);
        }
    }

    /**
     * 命令成功后的结构化结果。
     */
    record Reply(String type, String topic, Boolean recovered, String headOffset,
                 String state, Map<String, String> subscriptions) implements Serializable {

        public static Reply simple(String type, String topic) {
            return new Reply(type, topic, null, null, null, null);
        }
    }

    /**
     * 协议错误，不暴露服务端异常堆栈和敏感载荷。
     */
    record ProtocolError(String code, String message, Boolean snapshotRequired) implements Serializable {
    }

    /**
     * 服务端主动推送的领域事件帧。
     */
    record EventFrame(String frame, String eventId, String topic, String event,
                      String offset, Long timestamp, boolean replay,
                      Object data) implements JsonWebSocketMessage {

        public EventFrame(String eventId, String topic, String event, String offset,
                          Long timestamp, boolean replay, Object data) {
            this(FRAME_EVENT, eventId, topic, event, offset, timestamp, replay, data);
        }
    }
}
