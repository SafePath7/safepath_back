package com.safepath.backend.websocket;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class WebSocketSessionEventListener {
    private final InMemoryChannelRegistry channelRegistry;

    public WebSocketSessionEventListener(InMemoryChannelRegistry channelRegistry) {
        this.channelRegistry = channelRegistry;
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        channelRegistry.leaveAll(event.getSessionId());
    }
}
