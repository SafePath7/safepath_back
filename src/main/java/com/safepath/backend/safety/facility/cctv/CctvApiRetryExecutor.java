package com.safepath.backend.safety.facility.cctv;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CctvApiRetryExecutor {
    private static final Logger log = LoggerFactory.getLogger(CctvApiRetryExecutor.class);

    private final int maxAttempts;
    private final long initialDelayMillis;
    private final long maxDelayMillis;

    CctvApiRetryExecutor(int maxAttempts, long initialDelayMillis, long maxDelayMillis) {
        if (maxAttempts < 1 || initialDelayMillis < 0 || maxDelayMillis < initialDelayMillis) {
            throw new IllegalArgumentException("Invalid CCTV API retry configuration");
        }
        this.maxAttempts = maxAttempts;
        this.initialDelayMillis = initialDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
    }

    <T> T execute(int pageNo, Supplier<T> request) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return request.get();
            } catch (CctvApiRequestException e) {
                lastFailure = e;
                if (!e.isTransientFailure() || attempt == maxAttempts) {
                    log.error("CCTV API request failed: page={}, attempt={}/{}, httpStatus={}, retryable={}",
                            pageNo, attempt, maxAttempts, e.httpStatus(), e.isTransientFailure(), e);
                    break;
                }
            }

            long delayMillis = delayMillis(attempt);
            log.warn("CCTV API transient failure: page={}, attempt={}/{}, retryInMs={}",
                    pageNo, attempt, maxAttempts, delayMillis, lastFailure);
            waitBeforeRetry(delayMillis);
        }
        throw new CctvApiRetryExhaustedException(pageNo, maxAttempts, (CctvApiRequestException) lastFailure);
    }

    private long delayMillis(int attempt) {
        long multiplier = 1L << Math.min(attempt - 1, 30);
        return Math.min(initialDelayMillis * multiplier, maxDelayMillis);
    }

    private void waitBeforeRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CCTV API retry was interrupted", e);
        }
    }
}
