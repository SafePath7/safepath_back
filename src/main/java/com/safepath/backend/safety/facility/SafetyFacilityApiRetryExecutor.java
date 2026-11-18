package com.safepath.backend.safety.facility;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SafetyFacilityApiRetryExecutor {
    private static final Logger log = LoggerFactory.getLogger(SafetyFacilityApiRetryExecutor.class);

    private final String facilityName;
    private final int maxAttempts;
    private final long initialDelayMillis;
    private final long maxDelayMillis;

    public SafetyFacilityApiRetryExecutor(String facilityName, int maxAttempts, long initialDelayMillis, long maxDelayMillis) {
        if (maxAttempts < 1 || initialDelayMillis < 0 || maxDelayMillis < initialDelayMillis) {
            throw new IllegalArgumentException("Invalid safety facility API retry configuration");
        }
        this.facilityName = facilityName;
        this.maxAttempts = maxAttempts;
        this.initialDelayMillis = initialDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
    }

    public <T> T execute(int pageNo, Supplier<T> request) {
        SafetyFacilityApiRequestException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return request.get();
            } catch (SafetyFacilityApiRequestException e) {
                lastFailure = e;
                if (!e.isRetryable() || attempt == maxAttempts) {
                    log.error("{} API request failed: page={}, attempt={}/{}, httpStatus={}, retryable={}",
                            facilityName, pageNo, attempt, maxAttempts, e.httpStatus(), e.isRetryable(), e);
                    break;
                }
            }
            long delayMillis = delayMillis(attempt);
            log.warn("{} API transient failure: page={}, attempt={}/{}, httpStatus={}, retryInMs={}",
                    facilityName, pageNo, attempt, maxAttempts, lastFailure.httpStatus(), delayMillis, lastFailure);
            waitBeforeRetry(delayMillis);
        }
        throw new SafetyFacilityApiRetryExhaustedException(facilityName, pageNo, maxAttempts, lastFailure);
    }

    private long delayMillis(int attempt) {
        long multiplier = 1L << Math.min(attempt - 1, 30);
        return Math.min(initialDelayMillis * multiplier, maxDelayMillis);
    }

    private void waitBeforeRetry(long delayMillis) {
        waitFor(delayMillis, "Safety facility API retry was interrupted");
    }

    public static void waitFor(long delayMillis, String interruptionMessage) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interruptionMessage, e);
        }
    }
}
