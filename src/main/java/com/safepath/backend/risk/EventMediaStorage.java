package com.safepath.backend.risk;

import org.springframework.web.multipart.MultipartFile;

public interface EventMediaStorage {
    StoredFile store(MultipartFile file);
    void delete(String storageUrl);
    Deletion deleteWithRollback(String storageUrl);

    record StoredFile(String storageUrl, String fileName) {}

    @FunctionalInterface
    interface Deletion {
        void restore();
    }
}
