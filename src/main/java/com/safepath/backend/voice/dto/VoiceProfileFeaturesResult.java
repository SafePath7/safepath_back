package com.safepath.backend.voice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/** Internal AI features; the HTTP response contract is separate. */
public record VoiceProfileFeaturesResult(float[] speakerEmbedding, double baselineVolume, double baselinePitch,
        String modelVersion, @JsonInclude(JsonInclude.Include.ALWAYS) JsonNode baselineDetails) {
    public VoiceProfileFeaturesResult {
        speakerEmbedding = speakerEmbedding.clone();
        baselineDetails = baselineDetails == null ? null : baselineDetails.deepCopy();
    }

    @Override public float[] speakerEmbedding() { return speakerEmbedding.clone(); }

    @Override public JsonNode baselineDetails() {
        return baselineDetails == null ? null : baselineDetails.deepCopy();
    }

    @Override public String toString() { return "VoiceProfileFeaturesResult[sensitive fields redacted]"; }
}
