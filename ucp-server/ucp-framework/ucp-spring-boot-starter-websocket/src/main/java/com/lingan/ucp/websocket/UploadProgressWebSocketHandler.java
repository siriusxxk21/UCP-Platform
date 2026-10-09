package com.lingan.ucp.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 旧仓库上传链路的进度 WebSocket 处理器。
 * 该兼容组件只负责会话订阅和进度广播，不参与新制品中心的传输任务执行。
 */
@Slf4j
@Component
public class UploadProgressWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        log.info("上传进度 WebSocket 连接建立: {}, 当前连接数: {}", sessionId, sessions.size());
        sendMessage(sessionId, new WebSocketMessage("CONNECTED", "连接成功", null));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            WebSocketMessage request = objectMapper.readValue(message.getPayload(), WebSocketMessage.class);
            if ("SUBSCRIBE".equals(request.getType()) && request.getTaskId() != null) {
                session.getAttributes().put("subscribedTaskId", request.getTaskId());
                sendMessage(session.getId(), new WebSocketMessage("SUBSCRIBED", "订阅成功", request.getTaskId()));
            }
        } catch (Exception exception) {
            log.warn("处理上传进度 WebSocket 消息失败, sessionId={}", session.getId(), exception);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("上传进度 WebSocket 连接关闭: {}, 当前连接数: {}", session.getId(), sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session.getId());
        log.warn("上传进度 WebSocket 传输错误, sessionId={}", session.getId(), exception);
    }

    /**
     * 向订阅指定任务或全部任务的客户端发送进度。
     */
    public void sendProgressUpdate(Long taskId, ProgressData data) {
        String taskIdText = String.valueOf(taskId);
        WebSocketMessage message = new WebSocketMessage("PROGRESS", "进度更新", taskIdText, data);
        sessions.values().forEach(session -> {
            String subscribedTaskId = (String) session.getAttributes().get("subscribedTaskId");
            if (subscribedTaskId == null || taskIdText.equals(subscribedTaskId) || "ALL".equals(subscribedTaskId)) {
                sendMessage(session, message);
            }
        });
    }

    private void sendMessage(String sessionId, WebSocketMessage message) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null) sendMessage(session, message);
    }

    private void sendMessage(WebSocketSession session, WebSocketMessage message) {
        if (!session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (IOException exception) {
            log.warn("发送上传进度 WebSocket 消息失败, sessionId={}", session.getId(), exception);
        }
    }

    /**
     * 上传进度 WebSocket 消息。
     */
    @Data
    public static class WebSocketMessage {
        private String type;
        private String message;
        private String taskId;
        private Object data;
        private Long timestamp;

        public WebSocketMessage() {
            this.timestamp = System.currentTimeMillis();
        }

        public WebSocketMessage(String type, String message, String taskId) {
            this();
            this.type = type;
            this.message = message;
            this.taskId = taskId;
        }

        public WebSocketMessage(String type, String message, String taskId, Object data) {
            this(type, message, taskId);
            this.data = data;
        }
    }

    /**
     * 旧上传任务进度数据。
     */
    @Data
    public static class ProgressData {
        private Long taskId;
        private String status;
        private Integer progress;
        private String currentFile;
        private Integer currentFileIndex;
        private Integer totalFiles;
        private String errorMsg;
        private Long timestamp = System.currentTimeMillis();
    }
}
