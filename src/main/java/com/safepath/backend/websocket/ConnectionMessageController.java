package com.safepath.backend.websocket;

import com.safepath.backend.websocket.dto.ConnectionMessageRequest;
import com.safepath.backend.websocket.dto.ConnectionMessageResponse;
import jakarta.validation.Valid;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

import java.time.Instant;

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
            @Valid ConnectionMessageRequest request
    ) {
        if (!channelRegistry.exists(connectionId)) {
            throw new IllegalArgumentException("존재하지 않는 WebSocket 채널입니다.");
        }

        return new ConnectionMessageResponse(
                connectionId,
                request.senderId(),
                request.content(),
                Instant.now()
        );
    }
}
