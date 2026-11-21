package com.safepath.backend.voice.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.safepath.backend.voice.storage.VoiceAudioStorageException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VoiceAudioStorageTest {
    static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final int MAX_BYTES = VoiceAudioFileValidator.MAX_FILE_BYTES;
    private static final String TEST_REFERENCE = "voice-audio:" + UUID.randomUUID();

    @TempDir Path root;
    private VoiceAudioEncryption encryption;
    private VoiceAudioStorage storage;

    @BeforeEach
    void initializeStorage() throws IOException {
        root = root.toRealPath();
        encryption = new VoiceAudioEncryption(TEST_KEY);
        storage = new LocalEncryptedVoiceAudioStorage(root.toString(), encryption);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wav", "m4a", "mp3"})
    void acceptsSupportedAudioAndReturnsExactOriginal(String extension) throws IOException {
        byte[] original = switch (extension) {
            case "wav" -> wave(46);
            case "m4a" -> m4a();
            default -> mp3();
        };
        String type = switch (extension) {
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4";
            default -> "audio/mpeg";
        };
        String reference = store("recording." + extension, type, original);
        assertTrue(reference.startsWith("voice-audio:"));
        assertTrue(storage.exists(reference));
        assertArrayEquals(original, storage.read(reference));
        assertFalse(Arrays.equals(original, Files.readAllBytes(file(reference))));
    }

    @Test
    void acceptsExactlyTenMebibytesWithBoundedInput() {
        byte[] original = wave(MAX_BYTES);
        String reference = store("recording.WAV", "application/octet-stream", original);
        assertArrayEquals(original, storage.read(reference));
    }

    @Test
    void rejectsMissingEmptyAndIncorrectlyDeclaredInputs() {
        assertReason(INVALID_AUDIO, () -> storage.store(null, null, 0, null));
        assertReason(INVALID_AUDIO, () -> store("test.wav", null, new byte[0]));
        byte[] original = wave(46);
        assertReason(INVALID_AUDIO,
                () -> storage.store("test.wav", null, 1, new ByteArrayInputStream(original)));
    }

    @Test
    void rejectsOversizeDeclarationAndUnderreportedStream() {
        assertReason(TOO_LARGE, () -> storage.store("test.wav", null, MAX_BYTES + 1L, InputStream.nullInputStream()));
        assertReason(TOO_LARGE, () -> storage.store("test.wav", null, 46,
                new ByteArrayInputStream(wave(MAX_BYTES + 2))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"recording.ogg", "../recording.wav", "a\\recording.wav", "C:recording.wav"})
    void rejectsUnsupportedOrUnsafeNames(String name) {
        assertReason(INVALID_AUDIO, () -> store(name, "audio/wav", wave(46)));
    }

    @Test
    void rejectsWrongExtensionContentTypeAndDamagedStructures() {
        assertReason(INVALID_AUDIO, () -> store("recording.mp3", "audio/mpeg", wave(46)));
        assertReason(INVALID_AUDIO, () -> store("recording.wav", "audio/mpeg", wave(46)));
        assertReason(INVALID_AUDIO, () -> store("recording.wav", "audio/wav", new byte[46]));
        assertReason(INVALID_AUDIO, () -> store("recording.wav", null, Arrays.copyOf(wave(46), 45)));
        assertReason(INVALID_AUDIO, () -> store("recording.m4a", null, box("ftyp", ascii("M4A "))));
        assertReason(INVALID_AUDIO, () -> store("recording.mp3", null, Arrays.copyOf(mp3(), 100)));
        assertReason(INVALID_AUDIO, () -> store("recording.mp3", null, ascii("ID3")));
        assertReason(INVALID_AUDIO, () -> store("recording.wav", "audio/wav", mp3()));
        assertReason(INVALID_AUDIO, () -> store("recording.wav", "audio/wav", m4a()));
    }

    @Test
    void samePlaintextUsesDistinctNoncesAndCiphertexts() {
        byte[] original = wave(46);
        byte[] first = encryption.encrypt(original, TEST_REFERENCE);
        byte[] second = encryption.encrypt(original, TEST_REFERENCE);
        assertFalse(Arrays.equals(Arrays.copyOfRange(first, 5, 17), Arrays.copyOfRange(second, 5, 17)));
        assertFalse(Arrays.equals(Arrays.copyOfRange(first, 17, first.length),
                Arrays.copyOfRange(second, 17, second.length)));
        assertArrayEquals(original, encryption.decrypt(first, TEST_REFERENCE));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, 5, 17, 78})
    void rejectsAlteredMagicVersionNonceCiphertextOrTag(int offset) {
        byte[] stored = encryption.encrypt(wave(46), TEST_REFERENCE);
        stored[offset] ^= 1;
        assertReason(DECRYPTION_FAILED, () -> encryption.decrypt(stored, TEST_REFERENCE));
    }

    @Test
    void rejectsWrongKeyWrongReferenceAndTruncatedData() {
        byte[] key = new byte[32];
        key[0] = 1;
        var wrongKey = new VoiceAudioEncryption(Base64.getEncoder().encodeToString(key));
        byte[] stored = encryption.encrypt(wave(46), TEST_REFERENCE);
        assertReason(DECRYPTION_FAILED, () -> wrongKey.decrypt(stored, TEST_REFERENCE));
        assertReason(DECRYPTION_FAILED, () -> encryption.decrypt(stored, "voice-audio:" + UUID.randomUUID()));
        assertReason(DECRYPTION_FAILED, () -> encryption.decrypt(Arrays.copyOf(stored, 10), TEST_REFERENCE));
    }

    @Test
    void deletionIsIdempotentAndMissingReadIsExplicit() {
        String reference = store("same.wav", null, wave(46));
        storage.delete(reference);
        storage.delete(reference);
        assertFalse(storage.exists(reference));
        assertReason(NOT_FOUND, () -> storage.read(reference));
    }

    @Test
    void concurrentUploadsWithSameNameReceiveUniqueReferences() throws Exception {
        List<Callable<String>> requests = IntStream.range(0, 16)
                .mapToObj(index -> (Callable<String>) () -> store("same.wav", null, wave(46))).toList();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var references = new HashSet<String>();
            for (var future : executor.invokeAll(requests)) references.add(future.get());
            assertEquals(requests.size(), references.size());
            for (String reference : references) assertArrayEquals(wave(46), storage.read(reference));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../voice.wav", "C:\\voice.wav", "voice-audio:../target"})
    void rejectsPathsForEveryReferenceOperation(String reference) {
        assertReason(INVALID_REFERENCE, () -> storage.exists(reference));
        assertReason(INVALID_REFERENCE, () -> storage.read(reference));
        assertReason(INVALID_REFERENCE, () -> storage.delete(reference));
    }

    @Test
    void refusesToReadOrDeleteSymbolicLink() throws IOException {
        String reference = store("test.wav", null, wave(46));
        Path link = file(reference);
        Path outside = Files.createTempFile(root.getParent(), "voice-outside-", ".tmp");
        try {
            Files.delete(link);
            try {
                Files.createSymbolicLink(link, outside);
            } catch (IOException | UnsupportedOperationException | SecurityException exception) {
                assumeTrue(false, "OS does not permit symbolic-link creation");
            }
            assertReason(INVALID_REFERENCE, () -> storage.read(reference));
            assertReason(INVALID_REFERENCE, () -> storage.exists(reference));
            assertReason(INVALID_REFERENCE, () -> storage.delete(reference));
            assertTrue(Files.exists(outside));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void failedPublicationLeavesNoPartialFileAndPreservesExistingObject() throws IOException {
        Path target = root.resolve("existing.enc");
        byte[] original = {1, 2};
        Files.write(target, original);
        assertThrows(IOException.class, () -> LocalEncryptedVoiceAudioStorage.publish(target, new byte[]{3, 4}));
        assertArrayEquals(original, Files.readAllBytes(target));
        try (var children = Files.list(root)) {
            assertEquals(List.of(target), children.toList());
        }
    }

    @Test
    void streamFailureReturnsSanitizedStorageErrorAndLeavesNoFiles() throws IOException {
        var failure = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("private path"); }
        };
        var error = assertThrows(VoiceAudioStorageException.class,
                () -> storage.store("test.wav", null, -1, failure));
        assertEquals(STORAGE_FAILURE, error.reason());
        assertFalse(error.getMessage().contains("private path"));
        assertNull(error.getCause());
        try (var children = Files.list(root)) {
            assertEquals(0, children.count());
        }
    }

    private Path file(String reference) {
        return root.resolve(reference.substring("voice-audio:".length()) + ".enc");
    }

    private String store(String name, String type, byte[] audio) {
        return storage.store(name, type, audio.length, new ByteArrayInputStream(audio));
    }

    private static void assertReason(VoiceAudioStorageException.Reason reason,
            org.junit.jupiter.api.function.Executable operation) {
        assertEquals(reason, assertThrows(VoiceAudioStorageException.class, operation).reason());
    }

    private static byte[] wave(int length) {
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                .put(ascii("RIFF")).putInt(length - 8).put(ascii("WAVE"))
                .put(ascii("fmt ")).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
                .put(ascii("data")).putInt(length - 44).array();
    }

    private static byte[] m4a() throws IOException {
        byte[] handler = ByteBuffer.allocate(24).putInt(0).putInt(0).put(ascii("soun")).array();
        var file = new ByteArrayOutputStream();
        file.write(box("ftyp", ByteBuffer.allocate(12).put(ascii("M4A ")).putInt(0).put(ascii("isom")).array()));
        file.write(box("moov", box("trak", box("mdia", box("hdlr", handler)))));
        file.write(box("mdat", new byte[]{1, 2, 3}));
        return file.toByteArray();
    }

    private static byte[] mp3() {
        // Two MPEG-1 Layer III frames, 128 kbps at 44.1 kHz.
        var audio = ByteBuffer.allocate(834);
        audio.putInt(0xfffb9000);
        audio.position(417);
        audio.putInt(0xfffb9000);
        return audio.array();
    }

    private static byte[] box(String type, byte[] contents) {
        return ByteBuffer.allocate(8 + contents.length).putInt(8 + contents.length)
                .put(ascii(type)).put(contents).array();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
