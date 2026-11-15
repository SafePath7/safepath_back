package com.safepath.backend.risk.dto;

import java.time.Instant;

import com.safepath.backend.common.util.UtcTime;
import com.safepath.backend.risk.entity.RiskEventMedia;

/** Media as the API returns it: the internal storage location stays on the server. */
public record MediaResponse(long id, long eventId, String mediaType, String fileName,
        Instant uploadedAt, Instant createdAt, Instant updatedAt) {
    public static MediaResponse from(RiskEventMedia media) {
        return new MediaResponse(media.getId(), media.getEventId(), media.getMediaType(), media.getFileName(),
                UtcTime.toInstant(media.getUploadedAt()), UtcTime.toInstant(media.getCreatedAt()),
                UtcTime.toInstant(media.getUpdatedAt()));
    }
}
