package com.safepath.backend.websocket;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

@Component
public class WebSocketSessionEventListener {

    private static final String CHANNEL_TOPIC_PREFIX = "/topic/connections/";

    private final InMemoryChannelRegistry channelRegistry;

    public WebSocketSessionEventListener(InMemoryChannelRegistry channelRegistry) {
        this.channelRegistry = channelRegistry;
    }

    @EventListener
    public void handleSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        String sessionId = accessor.getSessionId();

        if (destination == null || sessionId == null || !destination.startsWith(CHANNEL_TOPIC_PREFIX)) {
            return;
        }

        String channelId = destination.substring(CHANNEL_TOPIC_PREFIX.length());
        String participantIdHeader = accessor.getFirstNativeHeader("participant-id");
        if (participantIdHeader == null) {
            throw new IllegalArgumentException("participant-id 헤더는 필수입니다.");
        }

        channelRegistry.join(channelId, Long.valueOf(participantIdHeader), sessionId);
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        channelRegistry.leaveAll(event.getSessionId());
    }
}
