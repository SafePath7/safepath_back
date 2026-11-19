package com.safepath.backend.voice.dto;

import java.time.Instant;

/** Internal extracted data for create/update; not an approved HTTP request. */
public record VoiceProfileCommand(float[] speakerEmbedding, String audioFileUrl,
        double baselineVolume, double baselinePitch, String modelVersion, Instant consentedAt) {
    public VoiceProfileCommand {
        speakerEmbedding = speakerEmbedding == null ? null : speakerEmbedding.clone();
    }

    @Override public float[] speakerEmbedding() {
        return speakerEmbedding == null ? null : speakerEmbedding.clone();
    }

    @Override public String toString() { return "VoiceProfileCommand[sensitive fields redacted]"; }
}
