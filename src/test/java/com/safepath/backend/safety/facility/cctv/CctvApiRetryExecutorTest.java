package com.safepath.backend.safety.facility.cctv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;

import com.safepath.backend.safety.facility.cctv.exception.CctvApiRequestException;
import com.safepath.backend.safety.facility.cctv.exception.CctvApiRetryExhaustedException;

import org.junit.jupiter.api.Test;

class CctvApiRetryExecutorTest {
    @Test
    void retriesTransientHttpFailuresWithConfiguredAttemptCount() {
        CctvApiRetryExecutor executor = new CctvApiRetryExecutor(3, 0, 0);
        AtomicInteger attempts = new AtomicInteger();

        String result = executor.execute(2500, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw CctvApiRequestException.httpFailure(2500, 503, "Service Unavailable", null);
            }
            return "success";
        });

        assertEquals("success", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void recordsPageWhenAllRetriesFail() {
        CctvApiRetryExecutor executor = new CctvApiRetryExecutor(2, 0, 0);

        CctvApiRetryExhaustedException exception = assertThrows(CctvApiRetryExhaustedException.class, () -> executor.execute(2500,
                () -> {
                    throw CctvApiRequestException.httpFailure(2500, 503, "Service Unavailable", null);
                }));

        assertEquals("CCTV API request failed after 2 attempts for page 2500", exception.getMessage());
    }
}
