package com.pulsar.copilot.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class CoPilotWebSocketConfig implements WebSocketConfigurer {

    private final CoPilotHandler handler;

    public CoPilotWebSocketConfig(CoPilotHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Single channel today (staff mic only — RC iframe doesn't expose remote
        // audio). When the WebPhone SDK hoist lands we'll add /ws/copilot/patient
        // alongside this and tag frames by URL path.
        registry.addHandler(handler, "/ws/copilot").setAllowedOriginPatterns("*");
    }
}
