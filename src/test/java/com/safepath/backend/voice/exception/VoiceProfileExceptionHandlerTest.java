package com.safepath.backend.voice.exception;

import com.safepath.backend.voice.exception.VoiceProfileException.Reason;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VoiceProfileExceptionHandlerTest {
    private final VoiceProfileExceptionHandler handler = new VoiceProfileExceptionHandler();

    @ParameterizedTest
    @CsvSource({
        "ALREADY_EXISTS, 409, CONFLICT",
        "INVALID_INPUT, 400, INVALID_REQUEST",
        "NOT_FOUND, 404, NOT_FOUND",
        "CONSTRAINT_VIOLATION, 500, INTERNAL_ERROR",
        "DATABASE_ERROR, 500, INTERNAL_ERROR"
    })
    void mapsDomainFailureWithoutExposingInternalDetails(Reason reason, int status, String code) {
        String internalDetail = "synthetic private object key and database details";
        var problem = handler.handleVoiceProfileException(new VoiceProfileException(reason, internalDetail));

        assertEquals(status, problem.getStatus());
        assertEquals(code, problem.getProperties().get("code"));
        assertFalse(problem.getDetail().contains(internalDetail));
    }
}
