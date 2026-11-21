package com.safepath.backend.voice.dto;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** Internal extracted data for create/update; not an approved HTTP request. */
public record VoiceProfileCommand(float[] speakerEmbedding, String audioFileUrl,
        double baselineVolume, double baselinePitch, String modelVersion, Instant consentedAt,
        JsonNode baselineDetails, String urgentAudioFileUrl) {
    public VoiceProfileCommand(float[] speakerEmbedding, String audioFileUrl,
            double baselineVolume, double baselinePitch, String modelVersion, Instant consentedAt) {
        this(speakerEmbedding, audioFileUrl, baselineVolume, baselinePitch, modelVersion, consentedAt, null, null);
    }

    public VoiceProfileCommand {
        speakerEmbedding = speakerEmbedding == null ? null : speakerEmbedding.clone();
        baselineDetails = baselineDetails == null ? null : baselineDetails.deepCopy();
    }

    @Override public JsonNode baselineDetails() {
        return baselineDetails == null ? null : baselineDetails.deepCopy();
    }

    @Override public float[] speakerEmbedding() {
        return speakerEmbedding == null ? null : speakerEmbedding.clone();
    }

    @Override public String toString() { return "VoiceProfileCommand[sensitive fields redacted]"; }
}
