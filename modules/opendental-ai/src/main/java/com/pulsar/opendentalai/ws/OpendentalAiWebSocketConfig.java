package com.pulsar.opendentalai.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class OpendentalAiWebSocketConfig implements WebSocketConfigurer {

    private final OpendentalAiHandler handler;

    public OpendentalAiWebSocketConfig(OpendentalAiHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/opendental-ai").setAllowedOriginPatterns("*");
    }
}
