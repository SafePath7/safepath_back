package com.safepath.backend.safety.facility.cctv;

final class CctvApiRetryExhaustedException extends IllegalStateException {
    private final boolean retryable;

    CctvApiRetryExhaustedException(int pageNo, int maxAttempts, CctvApiRequestException cause) {
        super("CCTV API request failed after " + maxAttempts + " attempts for page " + pageNo, cause);
        this.retryable = cause.isTransientFailure();
    }

    boolean isRetryable() {
        return retryable;
    }
}
