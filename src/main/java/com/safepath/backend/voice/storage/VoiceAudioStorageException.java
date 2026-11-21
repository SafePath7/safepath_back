package com.safepath.backend.voice.storage;

public class VoiceAudioStorageException extends RuntimeException {
    public enum Reason {
        INVALID_AUDIO, TOO_LARGE, INVALID_REFERENCE, NOT_FOUND, INVALID_CONFIGURATION, STORAGE_FAILURE, DECRYPTION_FAILED
    }

    private final Reason reason;

    public VoiceAudioStorageException(Reason reason) {
        super(switch (reason) {
            case INVALID_AUDIO -> "Voice audio is missing or invalid.";
            case TOO_LARGE -> "Voice audio exceeds the file size limit.";
            case INVALID_REFERENCE -> "Voice audio reference is invalid.";
            case NOT_FOUND -> "Voice audio was not found.";
            case INVALID_CONFIGURATION -> "Voice audio storage configuration is invalid.";
            case STORAGE_FAILURE -> "Voice audio storage operation failed.";
            case DECRYPTION_FAILED -> "Voice audio authentication or decryption failed.";
        });
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
