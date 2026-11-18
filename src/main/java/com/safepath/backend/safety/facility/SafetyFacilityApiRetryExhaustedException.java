package com.safepath.backend.safety.facility;

public final class SafetyFacilityApiRetryExhaustedException extends IllegalStateException {
    private final boolean retryable;

    SafetyFacilityApiRetryExhaustedException(
            String facilityName, int pageNo, int maxAttempts, SafetyFacilityApiRequestException cause) {
        super(facilityName + " API request failed after " + maxAttempts + " attempts for page " + pageNo, cause);
        this.retryable = cause.isRetryable();
    }

    public boolean isRetryable() { return retryable; }
}
