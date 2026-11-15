package com.safepath.backend.risk.dto;

import java.time.Instant;
import java.util.UUID;

import com.safepath.backend.common.util.UtcTime;
import com.safepath.backend.risk.entity.RiskEvent;

/** The result of creating or updating an event. */
public record RiskEventResponse(long id, UUID sessionId, String eventType, String description,
        String status, Instant detectedAt, Instant createdAt, Instant updatedAt) {
    public static RiskEventResponse from(RiskEvent event) {
        return new RiskEventResponse(event.getId(), event.getSessionId(), event.getEventType(),
                event.getDescription(), event.getStatus(), UtcTime.toInstant(event.getDetectedAt()),
                UtcTime.toInstant(event.getCreatedAt()), UtcTime.toInstant(event.getUpdatedAt()));
    }
}
