package com.safepath.backend.voice.exception;

/** Internal errors; HTTP mapping belongs to the later Controller integration. */
public class VoiceProfileException extends RuntimeException {
    public enum Reason { INVALID_INPUT, ALREADY_EXISTS, NOT_FOUND, CONSTRAINT_VIOLATION, DATABASE_ERROR }

    private final Reason reason;

    public VoiceProfileException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public VoiceProfileException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public static VoiceProfileException invalid(String field) {
        return new VoiceProfileException(Reason.INVALID_INPUT, "유효하지 않은 음성 프로필 입력: " + field);
    }
}
