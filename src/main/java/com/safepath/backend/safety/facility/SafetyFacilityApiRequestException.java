package com.safepath.backend.safety.facility;

public final class SafetyFacilityApiRequestException extends RuntimeException {
    private final Integer httpStatus;
    private final boolean retryable;

    private SafetyFacilityApiRequestException(String message, Integer httpStatus, boolean retryable, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public static SafetyFacilityApiRequestException httpFailure(
            String facilityName, int pageNo, int httpStatus, String statusText, Throwable cause) {
        return new SafetyFacilityApiRequestException(facilityName + " API HTTP request failed: page=" + pageNo
                + ", status=" + httpStatus + ", statusText=" + statusText, httpStatus,
                httpStatus == 429 || httpStatus == 502 || httpStatus == 503 || httpStatus == 504, cause);
    }

    public static SafetyFacilityApiRequestException networkFailure(String facilityName, int pageNo, Throwable cause) {
        return new SafetyFacilityApiRequestException(
                facilityName + " API network request failed: page=" + pageNo, null, true, cause);
    }

    public static SafetyFacilityApiRequestException emptyResponse(String facilityName, int pageNo, String missingPart) {
        return incompleteResponse(facilityName, pageNo, missingPart, null);
    }

    public static SafetyFacilityApiRequestException incompleteResponse(
            String facilityName, int pageNo, String missingPart, Throwable cause) {
        return new SafetyFacilityApiRequestException(
                facilityName + " API returned an incomplete response: page=" + pageNo + ", missing=" + missingPart,
                null, true, cause);
    }

    public Integer httpStatus() { return httpStatus; }
    public boolean isRetryable() { return retryable; }
}
