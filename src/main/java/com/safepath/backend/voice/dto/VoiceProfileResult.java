package com.safepath.backend.voice.dto;

import com.safepath.backend.common.util.UtcTime;
import com.safepath.backend.voice.VoiceProfile;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** Internal snapshot only. Do not serialize this as a future public GET response. */
public record VoiceProfileResult(Long userId, float[] speakerEmbedding, String audioFileUrl,
        double baselineVolume, double baselinePitch, String modelVersion,
        Instant consentedAt, Instant createdAt, Instant updatedAt, JsonNode baselineDetails, String urgentAudioFileUrl) {
    public VoiceProfileResult(Long userId, float[] speakerEmbedding, String audioFileUrl,
            double baselineVolume, double baselinePitch, String modelVersion,
            Instant consentedAt, Instant createdAt, Instant updatedAt) {
        this(userId, speakerEmbedding, audioFileUrl, baselineVolume, baselinePitch, modelVersion,
                consentedAt, createdAt, updatedAt, null, null);
    }

    public VoiceProfileResult {
        speakerEmbedding = speakerEmbedding.clone();
        baselineDetails = baselineDetails == null ? null : baselineDetails.deepCopy();
    }

    @Override public float[] speakerEmbedding() { return speakerEmbedding.clone(); }
    @Override public JsonNode baselineDetails() {
        return baselineDetails == null ? null : baselineDetails.deepCopy();
    }

    public static VoiceProfileResult from(VoiceProfile profile) {
        return new VoiceProfileResult(profile.getUserId(), profile.getSpeakerEmbedding(), profile.getAudioFileUrl(),
                profile.getBaselineVolume(), profile.getBaselinePitch(), profile.getModelVersion(),
                UtcTime.toInstant(profile.getConsentedAt()), UtcTime.toInstant(profile.getCreatedAt()),
                UtcTime.toInstant(profile.getUpdatedAt()), profile.getBaselineDetails(), profile.getUrgentAudioFileUrl());
    }

    @Override public String toString() { return "VoiceProfileResult[sensitive fields redacted]"; }
}
