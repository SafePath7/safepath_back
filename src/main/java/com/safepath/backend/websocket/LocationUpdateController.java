package com.safepath.backend.websocket;

import com.safepath.backend.websocket.dto.LocationUpdateRequest;
import com.safepath.backend.websocket.dto.LocationUpdateResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

@Controller
public class LocationUpdateController {
    private final InMemoryChannelRegistry channelRegistry;

    public LocationUpdateController(InMemoryChannelRegistry channelRegistry) {
        this.channelRegistry = channelRegistry;
    }

    @MessageMapping("/connections/{connectionId}/locations")
    @SendTo("/topic/connections/{connectionId}/locations")
    public LocationUpdateResponse relay(
            @DestinationVariable String connectionId,
            @Valid LocationUpdateRequest request,
            SimpMessageHeaderAccessor headerAccessor) {
        if (!channelRegistry.exists(connectionId)) {
            throw new IllegalArgumentException("Unknown WebSocket channel");
        }
        Long participantId = channelRegistry.participantIdForSession(connectionId, headerAccessor.getSessionId())
                .orElseThrow(() -> new IllegalArgumentException("WebSocket session has not joined this channel"));
        if (!channelRegistry.isLocationPublisher(connectionId, participantId)) {
            throw new IllegalArgumentException("Only the channel user may publish location updates");
        }
        return new LocationUpdateResponse(
                connectionId,
                String.valueOf(participantId),
                request.latitude(),
                request.longitude(),
                request.accuracyMeters(),
                request.capturedAt(),
                Instant.now());
    }
}
