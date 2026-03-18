package com.wenshape.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
public class SessionWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    
    // projectId -> Set<WebSocketSession>
    private final Map<String, Set<WebSocketSession>> projectSessions = new ConcurrentHashMap<>();

    public SessionWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String projectId = extractProjectId(session);
        if (projectId != null) {
            projectSessions.computeIfAbsent(projectId, k -> new CopyOnWriteArraySet<>()).add(session);
            
            Map<String, Object> message = Map.of(
                "type", "connected",
                "message", "Connected to WenShape session updates",
                "project_id", projectId
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
            log.info("WebSocket connected: projectId={}, sessionId={}", projectId, session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String projectId = extractProjectId(session);
        if (projectId != null) {
            Set<WebSocketSession> sessions = projectSessions.get(projectId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    projectSessions.remove(projectId);
                }
            }
            log.info("WebSocket disconnected: projectId={}, sessionId={}", projectId, session.getId());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 处理 ping/pong
        String payload = message.getPayload();
        Map<String, Object> response = Map.of(
            "type", "pong",
            "timestamp", payload
        );
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
    }

    /**
     * 向指定项目的所有连接广播消息
     */
    public void broadcast(String projectId, Map<String, Object> message) {
        Set<WebSocketSession> sessions = projectSessions.get(projectId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        
        try {
            String json = objectMapper.writeValueAsString(message);
            TextMessage textMessage = new TextMessage(json);
            
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(textMessage);
                    } catch (IOException e) {
                        log.warn("Failed to send message to session {}: {}", session.getId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to broadcast message: {}", e.getMessage());
        }
    }

    private String extractProjectId(WebSocketSession session) {
        String path = session.getUri().getPath();
        // /ws/{projectId}/session
        String[] parts = path.split("/");
        if (parts.length >= 3) {
            return parts[2];
        }
        return null;
    }
}
