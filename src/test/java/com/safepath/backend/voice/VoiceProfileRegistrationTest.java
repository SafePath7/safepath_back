package com.safepath.backend.voice;

import com.safepath.backend.voice.dto.VoiceProfileRegistration;
import com.safepath.backend.voice.dto.VoiceProfileRegistration.AudioInput;
import com.safepath.backend.voice.exception.VoiceProfileException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class VoiceProfileRegistrationTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final AudioInput AUDIO = new AudioInput("recording.wav", "audio/wav", -1,
            InputStream.nullInputStream());

    @Test
    void rejectsWrongDimensionsAndCopiesValidatedInput() {
        assertInvalid(() -> registration(new float[191], 0, 150, "model", null));
        float[] vector = unitVector(1);
        var details = JSON.createObjectNode().put("key", "original");
        var result = registration(vector, 0, 150, "model", details);
        vector[0] = 0;
        details.put("key", "changed");
        assertEquals(1, result.speakerEmbedding()[0]);
        assertEquals("original", result.baselineDetails().get("key").asText());
    }

    @ParameterizedTest
    @ValueSource(floats = {Float.NaN, Float.POSITIVE_INFINITY, 0.99f, 1.01f})
    void rejectsNonFiniteOrNonUnitEmbeddings(float first) {
        assertInvalid(() -> registration(unitVector(first), 0, 150, "model", null));
    }

    @Test
    void checksFiniteMeasurementsAndModelLengthWithoutApplyingQualityThresholds() {
        assertInvalid(() -> registration(unitVector(1), Double.NaN, 150, "model", null));
        assertInvalid(() -> registration(unitVector(1), 0, Double.NEGATIVE_INFINITY, "model", null));
        assertInvalid(() -> registration(unitVector(1), 0, 150, "a".repeat(51), null));
        assertDoesNotThrow(() -> registration(unitVector(1.0005f), -200, 0, "a".repeat(50), null));
    }

    @Test
    void rejectsNonObjectOversizedAndOverlyDeepDetails() {
        assertInvalid(() -> registration(unitVector(1), 0, 150, "model", JSON.createArrayNode()));
        assertInvalid(() -> registration(unitVector(1), 0, 150, "model",
                JSON.createObjectNode().put("large", "a".repeat(65536))));
        var details = JSON.createObjectNode();
        var nested = details;
        for (int depth = 0; depth < 32; depth++) nested = nested.putObject("child");
        assertInvalid(() -> registration(unitVector(1), 0, 150, "model", details));
        assertNull(registration(unitVector(1), 0, 150, "model", null).baselineDetails());
    }

    @Test
    void rejectsMissingCalmInput() {
        assertInvalid(() -> new VoiceProfileRegistration(unitVector(1), 0, 150, "model", null, null, null));
    }

    private static VoiceProfileRegistration registration(float[] vector, double volume, double pitch,
            String model, JsonNode details) {
        return new VoiceProfileRegistration(vector, volume, pitch, model, details, AUDIO, null);
    }

    private static float[] unitVector(float first) {
        var vector = new float[192];
        vector[0] = first;
        return vector;
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable operation) {
        assertEquals(VoiceProfileException.Reason.INVALID_INPUT,
                assertThrows(VoiceProfileException.class, operation).reason());
    }
}
