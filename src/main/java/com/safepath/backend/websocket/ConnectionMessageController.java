package com.safepath.backend.websocket;

import com.safepath.backend.websocket.dto.ConnectionMessageRequest;
import com.safepath.backend.websocket.dto.ConnectionMessageResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

@Controller
public class ConnectionMessageController {
    private final InMemoryChannelRegistry channelRegistry;

    public ConnectionMessageController(InMemoryChannelRegistry channelRegistry) {
        this.channelRegistry = channelRegistry;
    }

    @MessageMapping("/connections/{connectionId}/messages")
    @SendTo("/topic/connections/{connectionId}")
    public ConnectionMessageResponse relay(
            @DestinationVariable String connectionId,
            @Valid ConnectionMessageRequest request,
            SimpMessageHeaderAccessor headerAccessor) {
        if (!channelRegistry.exists(connectionId)) {
            throw new IllegalArgumentException("Unknown WebSocket channel");
        }
        Long participantId = channelRegistry.participantIdForSession(connectionId, headerAccessor.getSessionId())
                .orElseThrow(() -> new IllegalArgumentException("WebSocket session has not joined this channel"));
        if (!String.valueOf(participantId).equals(request.senderId())) {
            throw new IllegalArgumentException("senderId does not match the authenticated participant");
        }
        return new ConnectionMessageResponse(connectionId, String.valueOf(participantId), request.content(), Instant.now());
    }
}
