package com.safepath.backend.risk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import com.safepath.backend.guardianrisk.ApiException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
@ConditionalOnProperty(name = "safepath.media.provider", havingValue = "local", matchIfMissing = true)
public class LocalEventMediaStorage implements EventMediaStorage {
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private final Path root;

    public LocalEventMediaStorage(@Value("${safepath.media.root:./data/event-media}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(MultipartFile file) {
        if (file.isEmpty()) throw ApiException.badRequest("빈 파일은 업로드할 수 없습니다.");
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "파일은 10MB 이하여야 합니다.");
        }
        String key = UUID.randomUUID().toString();
        Path temporary = null;
        try {
            Files.createDirectories(root);
            temporary = Files.createTempFile(root, ".upload-", ".tmp");
            try (var stream = file.getInputStream()) {
                Files.copy(stream, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temporary, path(key), StandardCopyOption.ATOMIC_MOVE);
            return new StoredFile(key, safeFileName(file.getOriginalFilename()));
        } catch (IOException exception) {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* retained for cleanup */ }
            }
            throw storageError();
        }
    }

    @Override
    public byte[] read(String key) {
        try (var stream = Files.newInputStream(path(key))) {
            byte[] contents = stream.readNBytes(MAX_FILE_BYTES + 1);
            if (contents.length > MAX_FILE_BYTES) throw storageError();
            return contents;
        } catch (NoSuchFileException exception) {
            throw ApiException.notFound("미디어 파일");
        } catch (IOException exception) {
            throw storageError();
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (IOException exception) {
            throw storageError();
        }
    }

    @Override
    public Deletion deleteWithRollback(String key) {
        Path target = path(key);
        byte[] contents;
        try (var stream = Files.newInputStream(target)) {
            contents = stream.readNBytes(MAX_FILE_BYTES + 1);
        } catch (NoSuchFileException exception) {
            return () -> {};
        } catch (IOException exception) {
            throw storageError();
        }
        // Keep rollback memory bounded even if a file was changed outside the upload API.
        if (contents.length > MAX_FILE_BYTES) throw storageError();
        try {
            if (!Files.deleteIfExists(target)) return () -> {};
        } catch (IOException exception) {
            throw storageError();
        }
        return () -> {
            try {
                // Never overwrite a file created at this key after the deletion.
                Files.write(target, contents, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (IOException exception) {
                throw storageError();
            }
        };
    }

    private Path path(String key) {
        if (key == null || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw storageError();
        }
        Path result = root.resolve(key).normalize();
        if (!result.startsWith(root)) throw storageError();
        return result;
    }

    private String safeFileName(String original) {
        if (original == null || original.isBlank()) return "upload";
        String normalized = original.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (name.isBlank()) return "upload";
        int end = name.offsetByCodePoints(0, Math.min(name.codePointCount(0, name.length()), 255));
        return name.substring(0, end);
    }

    private ApiException storageError() {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_STORAGE_ERROR", "미디어 저장소 작업에 실패했습니다.");
    }
}
