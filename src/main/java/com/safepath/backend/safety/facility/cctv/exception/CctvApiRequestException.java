package com.safepath.backend.safety.facility.cctv.exception;

public final class CctvApiRequestException extends RuntimeException {
    private final Integer httpStatus;
    private final boolean transientFailure;

    private CctvApiRequestException(String message, Integer httpStatus, boolean transientFailure, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.transientFailure = transientFailure;
    }

    public static CctvApiRequestException httpFailure(int pageNo, int httpStatus, String statusText, Throwable cause) {
        return new CctvApiRequestException(
                "CCTV API HTTP request failed: page=" + pageNo + ", status=" + httpStatus
                        + ", statusText=" + statusText,
                httpStatus, httpStatus == 429 || httpStatus == 502 || httpStatus == 503 || httpStatus == 504, cause);
    }

    public static CctvApiRequestException networkFailure(int pageNo, Throwable cause) {
        return new CctvApiRequestException("CCTV API network request failed: page=" + pageNo, null, true, cause);
    }

    public Integer httpStatus() { return httpStatus; }
    public boolean isTransientFailure() { return transientFailure; }
}
