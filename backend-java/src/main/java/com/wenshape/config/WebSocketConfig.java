package com.wenshape.config;

import com.wenshape.websocket.SessionWebSocketHandler;
import com.wenshape.websocket.TraceWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SessionWebSocketHandler sessionWebSocketHandler;
    private final TraceWebSocketHandler traceWebSocketHandler;

    public WebSocketConfig(SessionWebSocketHandler sessionWebSocketHandler,
                          TraceWebSocketHandler traceWebSocketHandler) {
        this.sessionWebSocketHandler = sessionWebSocketHandler;
        this.traceWebSocketHandler = traceWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(sessionWebSocketHandler, "/ws/{projectId}/session")
                .setAllowedOrigins("*");
        
        registry.addHandler(traceWebSocketHandler, "/ws/trace")
                .setAllowedOrigins("*");
    }
}
