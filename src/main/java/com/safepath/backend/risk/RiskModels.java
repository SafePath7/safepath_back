package com.safepath.backend.risk;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.*;

public final class RiskModels {
    private RiskModels() {}

    public record EventRequest(@NotNull UUID sessionId,
            @NotBlank @Size(max = 20) String eventType,
            @NotBlank @Size(max = 500) String description, @NotNull Instant detectedAt) {}

    public record EventStatusRequest(
            @NotBlank @Size(max = 20) String status) {}

    public record RiskEvent(long id, UUID sessionId, String eventType, String description,
            String status, Instant detectedAt, Instant createdAt, Instant updatedAt) {}

    public record Media(long id, long eventId, String mediaType, String fileName,
            String storageUrl, Instant uploadedAt, Instant createdAt, Instant updatedAt) {}

}
