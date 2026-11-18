package com.safepath.backend.safety.facility.cctv.exception;

public final class CctvApiRetryExhaustedException extends IllegalStateException {
    private final boolean retryable;

    public CctvApiRetryExhaustedException(int pageNo, int maxAttempts, CctvApiRequestException cause) {
        super("CCTV API request failed after " + maxAttempts + " attempts for page " + pageNo, cause);
        this.retryable = cause.isTransientFailure();
    }

    public boolean isRetryable() { return retryable; }
}
