package com.safepath.backend.safety.facility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class SafetyFacilityApiRetryExecutorTest {
    @Test
    void retriesTransientHttpAndNetworkFailuresWithExponentialBackoffPolicy() {
        SafetyFacilityApiRetryExecutor executor = new SafetyFacilityApiRetryExecutor("Test facility", 3, 0, 0);
        AtomicInteger attempts = new AtomicInteger();

        String response = executor.execute(7, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw SafetyFacilityApiRequestException.httpFailure("Test facility", 7, 503, "Unavailable", null);
            }
            if (attempts.get() == 2) {
                throw SafetyFacilityApiRequestException.networkFailure("Test facility", 7, null);
            }
            return "ok";
        });

        assertEquals("ok", response);
        assertEquals(3, attempts.get());
    }

    @Test
    void doesNotRetryNonTransientHttpFailure() {
        SafetyFacilityApiRetryExecutor executor = new SafetyFacilityApiRetryExecutor("Test facility", 3, 0, 0);
        AtomicInteger attempts = new AtomicInteger();

        SafetyFacilityApiRetryExhaustedException exception = assertThrows(SafetyFacilityApiRetryExhaustedException.class,
                () -> executor.execute(7, () -> {
                    attempts.incrementAndGet();
                    throw SafetyFacilityApiRequestException.httpFailure("Test facility", 7, 400, "Bad Request", null);
                }));

        assertEquals(1, attempts.get());
        assertEquals(false, exception.isRetryable());
    }
}
