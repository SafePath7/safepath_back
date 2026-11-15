package com.safepath.backend.risk.dto;

import java.time.Instant;
import java.util.UUID;

import com.safepath.backend.common.util.UtcTime;
import com.safepath.backend.risk.entity.RiskEvent;

/** A risk event for read screens; userId is the member the event happened to. */
public record RiskEventDetailResponse(long id, long userId, UUID sessionId, String eventType, String description,
        String status, Instant detectedAt, Instant createdAt, Instant updatedAt) {
    public static RiskEventDetailResponse from(RiskEvent event) {
        return new RiskEventDetailResponse(event.getId(), event.getUserId(), event.getSessionId(),
                event.getEventType(), event.getDescription(), event.getStatus(),
                UtcTime.toInstant(event.getDetectedAt()), UtcTime.toInstant(event.getCreatedAt()),
                UtcTime.toInstant(event.getUpdatedAt()));
    }
}
