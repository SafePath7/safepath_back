package com.safepath.backend.voice.storage;

import java.io.InputStream;

public interface VoiceAudioStorage {
    /** Reads from the caller-owned stream without closing it. */
    String store(String fileName, String contentType, long declaredSize, InputStream contents);

    byte[] read(String reference);

    void delete(String reference);

    boolean exists(String reference);
}
