package com.safepath.backend.risk.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

public record EventRequest(@NotNull UUID sessionId,
        @NotBlank @Size(max = 20) String eventType,
        @NotBlank @Size(max = 500) String description, @NotNull Instant detectedAt,
        @JsonDeserialize(using = EventKeyDeserializer.class) UUID eventKey) {
    public EventRequest(UUID sessionId, String eventType, String description, Instant detectedAt) {
        this(sessionId, eventType, description, detectedAt, null);
    }
}
