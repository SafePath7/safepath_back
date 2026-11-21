package com.safepath.backend.voice.storage;

import java.io.InputStream;

public interface VoiceAudioStorage {
    static boolean isManagedReference(String reference) {
        return reference != null
                && reference.matches("voice-audio:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    /** Reads from the caller-owned stream without closing it. */
    String store(String fileName, String contentType, long declaredSize, InputStream contents);

    byte[] read(String reference);

    void delete(String reference);

    boolean exists(String reference);
}
