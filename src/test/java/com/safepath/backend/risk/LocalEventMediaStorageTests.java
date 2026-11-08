package com.safepath.backend.risk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.safepath.backend.common.ApiException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalEventMediaStorageTests {
    @TempDir Path directory;

    @Test
    void storesBytesUnderRandomKeyAndSanitizesOriginalName() throws Exception {
        var storage = new LocalEventMediaStorage(directory.resolve("media").toString());
        byte[] bytes = {1, 2, 3};
        var stored = storage.store(new MockMultipartFile("file", "..\\..\\recording.wav", "audio/wav", bytes));

        assertThat(stored.fileName()).isEqualTo("recording.wav");
        assertThat(stored.key()).matches("[0-9a-f-]{36}");
        assertThat(Files.readAllBytes(directory.resolve("media").resolve(stored.key()))).containsExactly(bytes);
        storage.delete(stored.key());
        storage.delete(stored.key());
        assertThat(Files.exists(directory.resolve("media").resolve(stored.key()))).isFalse();
    }

    @Test
    void refusesPathTraversalWithoutDeletingOtherFiles() throws Exception {
        var outside = directory.resolve("keep.txt");
        Files.writeString(outside, "keep");
        var storage = new LocalEventMediaStorage(directory.resolve("media").toString());

        assertThatThrownBy(() -> storage.delete("../keep.txt")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.deleteWithRollback("../keep.txt")).isInstanceOf(ApiException.class);
        assertThat(Files.readString(outside)).isEqualTo("keep");
    }

    @Test
    void filesystemFailureDoesNotOverwriteExistingFile() throws Exception {
        var root = directory.resolve("not-a-directory");
        Files.writeString(root, "keep");
        var storage = new LocalEventMediaStorage(root.toString());

        assertThatThrownBy(() -> storage.store(new MockMultipartFile("file", new byte[] {1})))
                .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.code()).isEqualTo("MEDIA_STORAGE_ERROR"));
        assertThat(Files.readString(root)).isEqualTo("keep");
    }

    @Test
    void reversibleDeletionRestoresTheOriginalBytes() throws Exception {
        var storage = new LocalEventMediaStorage(directory.toString());
        byte[] contents = {5, 6, 7};
        var stored = storage.store(new MockMultipartFile("file", contents));
        Path target = directory.resolve(stored.key());

        var deletion = storage.deleteWithRollback(stored.key());
        assertThat(Files.exists(target)).isFalse();
        deletion.restore();
        assertThat(Files.readAllBytes(target)).containsExactly(contents);
    }

    @Test
    void missingFileDeletionAndRollbackDoNotCreateAFile() {
        var storage = new LocalEventMediaStorage(directory.toString());
        String key = UUID.randomUUID().toString();
        storage.deleteWithRollback(key).restore();
        assertThat(Files.exists(directory.resolve(key))).isFalse();
    }

    @Test
    void rollbackNeverOverwritesAFileCreatedAtTheSameKey() throws Exception {
        var storage = new LocalEventMediaStorage(directory.toString());
        var stored = storage.store(new MockMultipartFile("file", new byte[] {1}));
        var deletion = storage.deleteWithRollback(stored.key());
        Path target = directory.resolve(stored.key());
        Files.write(target, new byte[] {9});

        assertThatThrownBy(deletion::restore).isInstanceOf(ApiException.class);
        assertThat(Files.readAllBytes(target)).containsExactly((byte) 9);
    }

    @Test
    void oversizedExistingFileIsPreservedWithoutAnUnboundedBackup() throws Exception {
        var storage = new LocalEventMediaStorage(directory.toString());
        String key = UUID.randomUUID().toString();
        Path target = directory.resolve(key);
        Files.write(target, new byte[10 * 1024 * 1024 + 1]);

        assertThatThrownBy(() -> storage.deleteWithRollback(key)).isInstanceOf(ApiException.class);
        assertThat(Files.size(target)).isEqualTo(10 * 1024 * 1024 + 1);
    }
}
