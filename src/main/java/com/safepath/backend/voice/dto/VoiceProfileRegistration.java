package com.safepath.backend.voice.dto;

import com.safepath.backend.voice.exception.VoiceProfileException;
import java.io.OutputStream;
import java.io.InputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Verified analysis input for registering one set of recordings. Streams remain caller-owned. */
public record VoiceProfileRegistration(float[] speakerEmbedding, double baselineVolume, double baselinePitch,
        String modelVersion, JsonNode baselineDetails, AudioInput calmAudio, AudioInput urgentAudio) {

    private static final int EMBEDDING_DIMENSIONS = 192;
    private static final double NORM_TOLERANCE = 0.001;
    private static final int MAX_DETAILS_BYTES = 64 * 1024;
    private static final int MAX_DETAILS_DEPTH = 32;
    private static final JsonMapper JSON = new JsonMapper();

    public VoiceProfileRegistration {
        if (speakerEmbedding == null || speakerEmbedding.length != EMBEDDING_DIMENSIONS) {
            throw VoiceProfileException.invalid("speakerEmbedding");
        }
        speakerEmbedding = speakerEmbedding.clone();
        double squaredNorm = 0;
        for (float element : speakerEmbedding) {
            if (!Float.isFinite(element)) throw VoiceProfileException.invalid("speakerEmbedding");
            squaredNorm += (double) element * element;
        }
        if (Math.abs(Math.sqrt(squaredNorm) - 1.0) > NORM_TOLERANCE) {
            throw VoiceProfileException.invalid("speakerEmbedding");
        }
        if (!Double.isFinite(baselineVolume)) throw VoiceProfileException.invalid("baselineVolume");
        if (!Double.isFinite(baselinePitch)) throw VoiceProfileException.invalid("baselinePitch");
        if (modelVersion == null || modelVersion.isBlank() || modelVersion.codePointCount(0, modelVersion.length()) > 50) {
            throw VoiceProfileException.invalid("modelVersion");
        }
        if (calmAudio == null || calmAudio.contents() == null) throw VoiceProfileException.invalid("calmAudio");
        if (urgentAudio != null && urgentAudio.contents() == null) throw VoiceProfileException.invalid("urgentAudio");
        if (baselineDetails != null) {
            if (!baselineDetails.isObject()) throw VoiceProfileException.invalid("baselineDetails");
            validateDepth(baselineDetails, 1);
            validateSize(baselineDetails);
            baselineDetails = baselineDetails.deepCopy();
        }
    }

    private static void validateDepth(JsonNode node, int depth) {
        if (!node.isObject() && !node.isArray()) return;
        if (depth > MAX_DETAILS_DEPTH) throw VoiceProfileException.invalid("baselineDetails");
        for (JsonNode child : node) validateDepth(child, depth + 1);
    }

    private static void validateSize(JsonNode node) {
        try {
            JSON.writeValue(new OutputStream() {
                private int remaining = MAX_DETAILS_BYTES;

                @Override public void write(int value) { consume(1); }

                @Override public void write(byte[] bytes, int offset, int length) { consume(length); }

                private void consume(int length) {
                    remaining -= length;
                    if (remaining < 0) throw VoiceProfileException.invalid("baselineDetails");
                }
            }, node);
        } catch (JacksonException exception) {
            throw VoiceProfileException.invalid("baselineDetails");
        }
    }

    @Override public float[] speakerEmbedding() { return speakerEmbedding.clone(); }

    @Override public JsonNode baselineDetails() {
        return baselineDetails == null ? null : baselineDetails.deepCopy();
    }

    @Override public String toString() { return "VoiceProfileRegistration[sensitive fields redacted]"; }

    public record AudioInput(String fileName, String contentType, long declaredSize, InputStream contents) {
        @Override public String toString() { return "AudioInput[sensitive fields redacted]"; }
    }
}
