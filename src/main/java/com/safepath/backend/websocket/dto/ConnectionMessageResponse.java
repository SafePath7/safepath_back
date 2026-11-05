package com.safepath.backend.websocket.dto;

import java.time.Instant;

public record ConnectionMessageResponse(
        String connectionId,
        String senderId,
        String content,
        Instant sentAt
) {
}
