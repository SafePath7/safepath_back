package com.safepath.backend.voice.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.safepath.backend.voice.storage.VoiceAudioStorageException.Reason.*;

final class LocalEncryptedVoiceAudioStorage implements VoiceAudioStorage {
    private static final String PREFIX = "voice-audio:";
    private static final Pattern REFERENCE = Pattern.compile(
            "voice-audio:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final int MAX_STORED_BYTES = VoiceAudioFileValidator.MAX_FILE_BYTES + VoiceAudioEncryption.OVERHEAD;

    private final Path root;
    private final VoiceAudioEncryption encryption;

    LocalEncryptedVoiceAudioStorage(String configuredPath, VoiceAudioEncryption encryption) {
        this.encryption = encryption;
        try {
            Path configured = Path.of(configuredPath);
            if (!configured.isAbsolute() || !configured.equals(configured.normalize())) {
                throw new VoiceAudioStorageException(INVALID_CONFIGURATION);
            }
            root = configured;
            if (root.getParent() == null || root.startsWith(Path.of("").toAbsolutePath().normalize())) {
                throw new VoiceAudioStorageException(INVALID_CONFIGURATION);
            }
            for (Path component : root) {
                if (switch (component.toString().toLowerCase(Locale.ROOT)) {
                    case "static", "public", "webapp", "webjars", "resources" -> true;
                    default -> false;
                }) throw new VoiceAudioStorageException(INVALID_CONFIGURATION);
            }
            verifyRoot();
            // Probe actual write and no-replace publication support for the running account.
            Path probe = Files.createTempFile(root, ".probe-", ".tmp");
            Path link = root.resolve(".probe-" + UUID.randomUUID());
            try {
                Files.createLink(link, probe);
                Files.delete(link);
            } finally {
                Files.deleteIfExists(probe);
            }
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException | SecurityException exception) {
            throw new VoiceAudioStorageException(INVALID_CONFIGURATION);
        }
    }

    @Override
    public String store(String fileName, String contentType, long declaredSize, InputStream contents) {
        byte[] audio = VoiceAudioFileValidator.read(fileName, contentType, declaredSize, contents);
        String reference = PREFIX + UUID.randomUUID();
        byte[] encrypted;
        try {
            encrypted = encryption.encrypt(audio, reference);
        } finally {
            Arrays.fill(audio, (byte) 0);
        }
        Path target = path(reference);
        try {
            publish(target, encrypted);
            return reference;
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            throw new VoiceAudioStorageException(STORAGE_FAILURE);
        }
    }

    static void publish(Path target, byte[] encrypted) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
        boolean published = false;
        try {
            Files.write(temporary, encrypted);
            // A hard link publishes completed bytes atomically and rejects an existing target.
            Files.createLink(target, temporary);
            published = true;
            Files.delete(temporary);
        } catch (IOException | RuntimeException exception) {
            try {
                if (published) Files.deleteIfExists(target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            throw exception;
        }
    }

    @Override
    public byte[] read(String reference) {
        Path target = path(reference);
        try {
            if (!regularFileExists(target)) throw new VoiceAudioStorageException(NOT_FOUND);
            try (var stream = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                byte[] stored = stream.readNBytes(MAX_STORED_BYTES + 1);
                if (stored.length > MAX_STORED_BYTES) throw new VoiceAudioStorageException(DECRYPTION_FAILED);
                return encryption.decrypt(stored, reference);
            }
        } catch (NoSuchFileException exception) {
            throw new VoiceAudioStorageException(NOT_FOUND);
        } catch (IOException | SecurityException exception) {
            throw new VoiceAudioStorageException(STORAGE_FAILURE);
        }
    }

    @Override
    public void delete(String reference) {
        Path target = path(reference);
        try {
            if (regularFileExists(target)) Files.deleteIfExists(target);
        } catch (IOException | SecurityException exception) {
            throw new VoiceAudioStorageException(STORAGE_FAILURE);
        }
    }

    @Override
    public boolean exists(String reference) {
        Path target = path(reference);
        try {
            return regularFileExists(target);
        } catch (IOException | SecurityException exception) {
            throw new VoiceAudioStorageException(STORAGE_FAILURE);
        }
    }

    private Path path(String reference) {
        if (reference == null || !REFERENCE.matcher(reference).matches()) {
            throw new VoiceAudioStorageException(INVALID_REFERENCE);
        }
        try {
            verifyRoot();
            return root.resolve(reference.substring(PREFIX.length()) + ".enc");
        } catch (IOException | SecurityException exception) {
            throw new VoiceAudioStorageException(STORAGE_FAILURE);
        }
    }

    private void verifyRoot() throws IOException {
        Path current = root.getRoot();
        for (Path part : root) {
            current = current.resolve(part);
            if (!Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()) {
                throw new IOException();
            }
        }
        if (!root.equals(root.toRealPath())) throw new IOException();
    }

    private static boolean regularFileExists(Path file) throws IOException {
        try {
            if (!Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                throw new VoiceAudioStorageException(INVALID_REFERENCE);
            }
            return true;
        } catch (NoSuchFileException exception) {
            return false;
        }
    }
}
