package com.safepath.backend.websocket.dto;

import java.time.Instant;
import com.safepath.backend.session.model.SafetyStatus;

public record LocationUpdateResponse(
        String connectionId,
        String senderId,
        double latitude,
        double longitude,
        Double accuracyMeters,
        Instant capturedAt,
        Instant receivedAt,
        SafetyStatus safetyStatus
) {
}
