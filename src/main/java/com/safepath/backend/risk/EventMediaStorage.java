package com.safepath.backend.risk;

import org.springframework.web.multipart.MultipartFile;

public interface EventMediaStorage {
    StoredFile store(MultipartFile file);
    byte[] read(String key);
    void delete(String key);
    Deletion deleteWithRollback(String key);

    record StoredFile(String key, String fileName) {}

    @FunctionalInterface
    interface Deletion {
        void restore();
    }
}
