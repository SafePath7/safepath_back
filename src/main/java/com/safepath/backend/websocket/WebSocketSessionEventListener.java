package com.safepath.backend.websocket;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

@Component
public class WebSocketSessionEventListener {
    private static final String CHANNEL_TOPIC_PREFIX = "/topic/connections/";
    private static final String LOCATION_TOPIC_SUFFIX = "/locations";
    private final InMemoryChannelRegistry channelRegistry;

    public WebSocketSessionEventListener(InMemoryChannelRegistry channelRegistry) {
        this.channelRegistry = channelRegistry;
    }

    @EventListener
    public void handleSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        String sessionId = accessor.getSessionId();
        if (destination == null || sessionId == null || !destination.startsWith(CHANNEL_TOPIC_PREFIX)
                || !destination.endsWith(LOCATION_TOPIC_SUFFIX)) return;

        if (!(accessor.getUser() instanceof Authentication authentication)
                || !(authentication.getPrincipal() instanceof Long participantId)) {
            throw new IllegalStateException("Unauthenticated WebSocket session");
        }
        String channelId = destination.substring(
                CHANNEL_TOPIC_PREFIX.length(), destination.length() - LOCATION_TOPIC_SUFFIX.length());
        if (channelId.isBlank()) return;
        channelRegistry.join(channelId, participantId, sessionId);
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        channelRegistry.leaveAll(event.getSessionId());
    }
}
