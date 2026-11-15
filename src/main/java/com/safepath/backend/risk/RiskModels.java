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

    /** A risk event for read screens; userId is the member the event happened to. */
    public record RiskEventDetail(long id, long userId, UUID sessionId, String eventType, String description,
            String status, Instant detectedAt, Instant createdAt, Instant updatedAt) {}

    public record Media(long id, long eventId, String mediaType, String fileName,
            String storageUrl, Instant uploadedAt, Instant createdAt, Instant updatedAt) {}

    /** Media as the API returns it: the internal storage location stays on the server. */
    public record MediaInfo(long id, long eventId, String mediaType, String fileName,
            Instant uploadedAt, Instant createdAt, Instant updatedAt) {
        public static MediaInfo of(Media media) {
            return new MediaInfo(media.id(), media.eventId(), media.mediaType(), media.fileName(),
                    media.uploadedAt(), media.createdAt(), media.updatedAt());
        }
    }

}
