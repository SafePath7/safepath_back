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

    @MessageMapping("/connections/{connectionId}/messages")
    @SendTo("/topic/connections/{connectionId}")
    public ConnectionMessageResponse relay(
            @DestinationVariable String connectionId,
            @Valid ConnectionMessageRequest request
    ) {
        return new ConnectionMessageResponse(
                connectionId,
                request.senderId(),
                request.content(),
                Instant.now()
        );
    }
}
