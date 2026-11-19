package com.safepath.backend.voice.dto;

import com.safepath.backend.common.util.UtcTime;
import com.safepath.backend.voice.VoiceProfile;
import java.time.Instant;

/** Internal snapshot only. Do not serialize this as a future public GET response. */
public record VoiceProfileResult(Long userId, float[] speakerEmbedding, String audioFileUrl,
        double baselineVolume, double baselinePitch, String modelVersion,
        Instant consentedAt, Instant createdAt, Instant updatedAt) {
    public VoiceProfileResult { speakerEmbedding = speakerEmbedding.clone(); }

    @Override public float[] speakerEmbedding() { return speakerEmbedding.clone(); }

    public static VoiceProfileResult from(VoiceProfile profile) {
        return new VoiceProfileResult(profile.getUserId(), profile.getSpeakerEmbedding(), profile.getAudioFileUrl(),
                profile.getBaselineVolume(), profile.getBaselinePitch(), profile.getModelVersion(),
                UtcTime.toInstant(profile.getConsentedAt()), UtcTime.toInstant(profile.getCreatedAt()),
                UtcTime.toInstant(profile.getUpdatedAt()));
    }

    @Override public String toString() { return "VoiceProfileResult[sensitive fields redacted]"; }
}
