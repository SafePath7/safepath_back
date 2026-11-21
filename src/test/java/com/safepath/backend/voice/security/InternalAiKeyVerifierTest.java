package com.safepath.backend.voice.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class InternalAiKeyVerifierTest {
    private static final String TEST_KEY = "synthetic-internal-key";

    @Test
    void acceptsOnlyExactConfiguredKey() {
        var verifier = new InternalAiKeyVerifier(TEST_KEY);
        assertTrue(verifier.matches(TEST_KEY));
        assertFalse(verifier.matches("Synthetic-internal-key"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "wrong-test-key", " synthetic-internal-key", "synthetic-internal-key\n"})
    void deniesMissingBlankMalformedOrWrongRequestKey(String supplied) {
        assertFalse(new InternalAiKeyVerifier(TEST_KEY).matches(supplied));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " configured-with-leading-space", "invalid\nkey"})
    void unusableConfigurationDeniesEveryRequest(String configured) {
        var verifier = new InternalAiKeyVerifier(configured);
        assertFalse(verifier.matches(configured));
        assertFalse(verifier.matches(TEST_KEY));
    }

    @Test
    void missingConfigurationKeepsContextAvailableAndFailsClosed() {
        new ApplicationContextRunner().withUserConfiguration(InternalAiKeyVerifier.class)
                .withPropertyValues("SAFEPATH_AI_INTERNAL_KEY=")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.getBean(InternalAiKeyVerifier.class).matches(TEST_KEY));
                });
    }

    @Test
    void springConfigurationBindsKeyWithoutAnExceptionOnRejectedInput() {
        new ApplicationContextRunner().withUserConfiguration(InternalAiKeyVerifier.class)
                .withPropertyValues("SAFEPATH_AI_INTERNAL_KEY=" + TEST_KEY)
                .run(context -> {
                    var verifier = context.getBean(InternalAiKeyVerifier.class);
                    assertTrue(verifier.matches(TEST_KEY));
                    assertFalse(assertDoesNotThrow(() -> verifier.matches("synthetic-invalid-request")));
                });
    }
}
