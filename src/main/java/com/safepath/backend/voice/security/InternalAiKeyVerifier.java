package com.safepath.backend.voice.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Verifies a server key without granting HTTP access or resolving a user identity. */
@Component
public class InternalAiKeyVerifier {
    public static final String HEADER_NAME = "X-Internal-Key";
    private final byte[] expectedDigest;

    public InternalAiKeyVerifier(@Value("${SAFEPATH_AI_INTERNAL_KEY:}") String configuredKey) {
        expectedDigest = usable(configuredKey) ? digest(configuredKey) : null;
    }

    public boolean matches(String suppliedKey) {
        return expectedDigest != null && usable(suppliedKey)
                && MessageDigest.isEqual(expectedDigest, digest(suppliedKey));
    }

    private static boolean usable(String key) {
        return key != null && !key.isBlank() && key.equals(key.strip())
                && key.codePoints().noneMatch(Character::isISOControl);
    }

    private static byte[] digest(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Internal key verification is unavailable.");
        }
    }
}
