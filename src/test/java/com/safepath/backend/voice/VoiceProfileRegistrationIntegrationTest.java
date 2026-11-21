package com.safepath.backend.voice;

import com.safepath.backend.voice.dto.VoiceProfileCommand;
import com.safepath.backend.voice.dto.VoiceProfileRegistration;
import com.safepath.backend.voice.dto.VoiceProfileRegistration.AudioInput;
import com.safepath.backend.voice.dto.VoiceProfileResult;
import com.safepath.backend.voice.exception.VoiceProfileException;
import com.safepath.backend.voice.storage.VoiceAudioStorage;
import com.safepath.backend.voice.storage.VoiceAudioStorageException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SAFEPATH_VOICE_TEST_DB_URL",
        matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):5433/safepath")
class VoiceProfileRegistrationIntegrationTest {
    private static final Instant OLD_TIME = Instant.parse("2000-01-01T00:00:00Z");
    private static final JsonMapper JSON = new JsonMapper();
    private static AnnotationConfigApplicationContext database;
    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager transactions;
    private static VoiceProfileRepository repository;

    @TempDir Path root;
    private AnnotationConfigApplicationContext storageContext;
    private VoiceAudioStorage storage;
    private VoiceProfileService profiles;
    private VoiceProfileRegistrationService registration;
    private long userId;
    private String fixtureUid;

    @BeforeAll
    static void connect() {
        database = new AnnotationConfigApplicationContext(VoiceProfileRepositoryTest.PersistenceConfig.class);
        repository = database.getBean(VoiceProfileRepository.class);
        jdbc = new JdbcTemplate(database.getBean(DataSource.class));
        transactions = database.getBean(PlatformTransactionManager.class);
    }

    @AfterAll
    static void disconnect() {
        if (database != null) database.close();
    }

    @BeforeEach
    void createFixture() throws IOException {
        root = root.toRealPath();
        storageContext = new AnnotationConfigApplicationContext();
        storageContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                "safepath.voice.audio.storage.enabled", true,
                "safepath.voice.audio.storage.path", root.toString(),
                "safepath.voice.audio.encryption.key", Base64.getEncoder().encodeToString(new byte[32]))));
        storageContext.scan("com.safepath.backend.voice.storage");
        storageContext.refresh();
        storage = spy(storageContext.getBean(VoiceAudioStorage.class));
        profiles = spy(new VoiceProfileService(repository));
        registration = serviceWith(storage);
        long candidateId = ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 2_000_000_000_000L);
        String marker = "voice-lifecycle-test-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, firebase_uid, name, email, phone_hash, phone_encrypted)
                VALUES (?, ?, 'voice lifecycle test', ?, ?, 'synthetic-fixture')
                """, candidateId, marker, marker + "@example.test", marker);
        userId = candidateId;
        fixtureUid = marker;
    }

    @AfterEach
    void removeFixture() {
        try {
            if (fixtureUid != null) {
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.update("""
                            DELETE FROM voice_profiles WHERE user_id =
                                (SELECT id FROM users WHERE id = ? AND firebase_uid = ?)
                            """, userId, fixtureUid);
                    jdbc.update("DELETE FROM users WHERE id = ? AND firebase_uid = ?", userId, fixtureUid);
                });
            }
        } finally {
            if (storageContext != null) storageContext.close();
        }
    }

    @Test
    void calmOnlyRegistrationCommitsAndRoundTrips() throws IOException {
        var result = registration.register(userId, request(false, "model"));
        assertEquals(result.audioFileUrl(), databaseProfile().audioFileUrl());
        assertNull(databaseProfile().urgentAudioFileUrl());
        assertArrayEquals(wave(), storage.read(result.audioFileUrl()));
        assertNull(databaseProfile().baselineDetails());
        assertEquals(1, fileCount());
    }

    @Test
    void registrationWithTwoRecordingsCommitsNestedJsonAndDistinctReferences() throws IOException {
        var result = registration.register(userId, request(true, "model"));
        var saved = databaseProfile();
        assertEquals(result.audioFileUrl(), saved.audioFileUrl());
        assertEquals(result.urgentAudioFileUrl(), saved.urgentAudioFileUrl());
        assertNotEquals(saved.audioFileUrl(), saved.urgentAudioFileUrl());
        assertArrayEquals(wave(), storage.read(saved.audioFileUrl()));
        assertArrayEquals(wave(), storage.read(saved.urgentAudioFileUrl()));
        assertEquals(JSON.readTree("{\"urgent\":{\"samples\":[1,2]}}"), saved.baselineDetails());
        assertEquals(2, fileCount());
    }

    @Test
    void reregisterWithoutUrgentPreservesCreationAndRemovesOldFilesAfterCommit() throws IOException {
        var old = registration.register(userId, request(true, "model"));
        jdbc.update("""
                UPDATE voice_profiles SET consented_at=TIMESTAMP '2000-01-01', updated_at=TIMESTAMP '2000-01-01'
                WHERE user_id=?
                """, userId);
        doAnswer(call -> {
            assertNotEquals(old.audioFileUrl(), databaseProfile().audioFileUrl());
            assertNull(databaseProfile().urgentAudioFileUrl());
            return call.callRealMethod();
        }).when(storage).delete(anyString());

        var result = registration.register(userId, request(false, "next-model"));
        assertEquals(old.createdAt(), result.createdAt());
        assertTrue(result.updatedAt().isAfter(OLD_TIME));
        assertTrue(result.consentedAt().isAfter(OLD_TIME));
        assertEquals("next-model", databaseProfile().modelVersion());
        assertNull(result.urgentAudioFileUrl());
        assertFalse(storage.exists(old.audioFileUrl()));
        assertFalse(storage.exists(old.urgentAudioFileUrl()));
        assertTrue(storage.exists(result.audioFileUrl()));
        assertEquals(1, fileCount());
    }

    @Test
    void invalidCalmOrUrgentAudioLeavesNoProfileAndNoFiles() throws IOException {
        var valid = request(false, "model");
        var invalid = new AudioInput("broken.wav", "audio/wav", 1, new ByteArrayInputStream(new byte[]{1}));
        assertThrows(VoiceAudioStorageException.class, () -> registration.register(userId,
                new VoiceProfileRegistration(embedding(), 0, 150, "model", null, invalid, null)));
        assertThrows(VoiceAudioStorageException.class, () -> registration.register(userId,
                new VoiceProfileRegistration(embedding(), 0, 150, "model", null, valid.calmAudio(),
                        new AudioInput("broken.wav", "audio/wav", 1, new ByteArrayInputStream(new byte[]{1})))));
        assertFalse(repository.existsById(userId));
        assertEquals(0, fileCount());
    }

    @Test
    void databaseFlushFailureRollsBackNewFilesAndPreservesOldProfile() throws IOException {
        var old = registration.register(userId, request(true, "model"));
        assertThrows(VoiceProfileException.class, () -> registration.register(userId, request(true, "invalid\0model")));
        assertEquals(old.audioFileUrl(), databaseProfile().audioFileUrl());
        assertEquals(old.urgentAudioFileUrl(), databaseProfile().urgentAudioFileUrl());
        assertTrue(storage.exists(old.audioFileUrl()));
        assertTrue(storage.exists(old.urgentAudioFileUrl()));
        assertEquals(2, fileCount());
    }

    @Test
    void deferredPostgresConstraintFailsAtCommitAndReconcilesOnlyNewFiles() throws IOException {
        var old = registration.register(userId, request(true, "model"));
        doAnswer(call -> {
            var result = call.callRealMethod();
            jdbc.execute("CREATE TEMP TABLE voice_commit_parent (id integer PRIMARY KEY) ON COMMIT DROP");
            jdbc.execute("""
                    CREATE TEMP TABLE voice_commit_child (
                        parent_id integer REFERENCES voice_commit_parent(id) DEFERRABLE INITIALLY DEFERRED
                    ) ON COMMIT DROP
                    """);
            jdbc.update("INSERT INTO voice_commit_child VALUES (1)");
            assertTrue(storage.exists(old.audioFileUrl()));
            return result;
        }).when(profiles).updateVoiceProfile(eq(userId), any(VoiceProfileCommand.class));

        var failure = assertThrows(RuntimeException.class, () -> registration.register(userId, request(true, "next")));
        assertTrue(hasSqlState(failure, "23503"));
        assertEquals(old.audioFileUrl(), databaseProfile().audioFileUrl());
        assertEquals(old.urgentAudioFileUrl(), databaseProfile().urgentAudioFileUrl());
        assertTrue(storage.exists(old.audioFileUrl()));
        assertTrue(storage.exists(old.urgentAudioFileUrl()));
        assertEquals(2, fileCount());
    }

    @Test
    void deleteAfterCommitHandlesMissingManagedFileAndSkipsLegacyReference() throws IOException {
        var old = registration.register(userId, request(true, "model"));
        storage.delete(old.urgentAudioFileUrl());
        jdbc.update("UPDATE voice_profiles SET urgent_audio_file_url = '/legacy/file.wav' WHERE user_id = ?", userId);
        registration.delete(userId);
        assertFalse(repository.existsById(userId));
        assertFalse(storage.exists(old.audioFileUrl()));
        verify(storage, never()).delete("/legacy/file.wav");
        storage.delete(old.audioFileUrl());
        assertEquals(0, fileCount());
    }

    @Test
    void rolledBackDeletionRetainsDatabaseAndRecordings() {
        var old = registration.register(userId, request(true, "model"));
        doAnswer(call -> {
            call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("fixture rollback");
                }
            });
            return null;
        }).when(profiles).deleteVoiceProfile(userId);

        assertThrows(IllegalStateException.class, () -> registration.delete(userId));
        assertEquals(old.audioFileUrl(), databaseProfile().audioFileUrl());
        assertTrue(storage.exists(old.audioFileUrl()));
        assertTrue(storage.exists(old.urgentAudioFileUrl()));
    }

    @Test
    void cleanupDoesNotDeleteReferenceStillUsedByDatabase() {
        var old = registration.register(userId, request(true, "model"));
        doAnswer(call -> {
            var result = (VoiceProfileResult) call.callRealMethod();
            // Simulate a file reference shared with an existing metadata entry.
            jdbc.update("UPDATE voice_profiles SET urgent_audio_file_url = ? WHERE user_id = ?",
                    old.audioFileUrl(), userId);
            return result;
        }).when(profiles).updateVoiceProfile(eq(userId), any(VoiceProfileCommand.class));

        registration.register(userId, request(false, "next"));
        assertEquals(old.audioFileUrl(), databaseProfile().urgentAudioFileUrl());
        assertTrue(storage.exists(old.audioFileUrl()));
        verify(storage, never()).delete(old.audioFileUrl());
    }

    @Test
    void committedRegistrationSucceedsWhenOldFileCleanupFailsAndRetryRemovesIt() {
        var old = registration.register(userId, request(false, "model"));
        doThrow(new VoiceAudioStorageException(VoiceAudioStorageException.Reason.STORAGE_FAILURE))
                .doCallRealMethod().when(storage).delete(old.audioFileUrl());
        var updated = registration.register(userId, request(false, "next"));
        assertEquals(updated.audioFileUrl(), databaseProfile().audioFileUrl());
        assertTrue(storage.exists(old.audioFileUrl()));
        assertEquals(0, registration.retryPendingCleanup());
        assertFalse(storage.exists(old.audioFileUrl()));
        assertTrue(storage.exists(updated.audioFileUrl()));
    }

    @Test
    void failedRollbackCleanupCanBeRetriedWithoutDeletingTheCurrentFile() throws IOException {
        var old = registration.register(userId, request(false, "model"));
        doThrow(new VoiceAudioStorageException(VoiceAudioStorageException.Reason.STORAGE_FAILURE))
                .when(storage).delete(argThat(reference -> !old.audioFileUrl().equals(reference)));
        assertThrows(VoiceProfileException.class, () -> registration.register(userId, request(false, "invalid\0model")));
        assertEquals(2, fileCount());
        assertEquals(1, registration.retryPendingCleanup());
        doCallRealMethod().when(storage).delete(anyString());
        assertEquals(0, registration.retryPendingCleanup());
        assertEquals(1, fileCount());
        assertTrue(storage.exists(old.audioFileUrl()));
    }

    @Test
    void absentMemberDisabledStorageAndAmbientTransactionFailBeforeFileWrites() throws IOException {
        var notFound = assertThrows(VoiceProfileException.class,
                () -> registration.register(-userId + Long.MAX_VALUE, request(false, "model")));
        assertEquals(VoiceProfileException.Reason.NOT_FOUND, notFound.reason());
        var disabled = assertThrows(VoiceAudioStorageException.class,
                () -> serviceWith(null).register(userId, request(false, "model")));
        assertEquals(VoiceAudioStorageException.Reason.STORAGE_DISABLED, disabled.reason());
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                assertThrows(IllegalStateException.class, () -> registration.register(userId, request(false, "model"))));
        assertFalse(repository.existsById(userId));
        assertEquals(0, fileCount());
    }

    @Test
    void concurrentFirstAndRepeatedRegistrationsLeaveOnlyFilesReferencedByFinalProfile() throws Exception {
        for (int round = 0; round < 2; round++) {
            try (var executor = Executors.newFixedThreadPool(2)) {
                List<Callable<VoiceProfileResult>> calls = List.of(
                        () -> registration.register(userId, request(true, "model-a")),
                        () -> registration.register(userId, request(true, "model-b")));
                var references = new HashSet<String>();
                for (var future : executor.invokeAll(calls)) references.add(future.get().audioFileUrl());
                var current = databaseProfile();
                assertEquals(2, references.size());
                assertTrue(references.contains(current.audioFileUrl()));
                assertTrue(storage.exists(current.audioFileUrl()));
                assertTrue(storage.exists(current.urgentAudioFileUrl()));
                assertEquals(2, fileCount());
            }
        }
    }

    @Test
    void concurrentRegistrationAndDeletionKeepDatabaseAndFilesConsistent() throws Exception {
        registration.register(userId, request(true, "model"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Void>> calls = List.of(
                    () -> { registration.register(userId, request(true, "next")); return null; },
                    () -> { registration.delete(userId); return null; });
            for (var future : executor.invokeAll(calls)) future.get();
        }
        var current = repository.findById(userId);
        if (current.isPresent()) {
            assertTrue(storage.exists(current.get().getAudioFileUrl()));
            assertTrue(storage.exists(current.get().getUrgentAudioFileUrl()));
            assertEquals(2, fileCount());
        } else {
            assertEquals(0, fileCount());
        }
    }

    private VoiceProfileRegistrationService serviceWith(VoiceAudioStorage audioStorage) {
        var factory = new StaticListableBeanFactory();
        if (audioStorage != null) factory.addBean("testStorage", audioStorage);
        return new VoiceProfileRegistrationService(repository, profiles, factory.getBeanProvider(VoiceAudioStorage.class),
                transactions);
    }

    private VoiceProfileResult databaseProfile() {
        return profiles.getVoiceProfile(userId);
    }

    private long fileCount() throws IOException {
        try (var files = Files.list(root)) { return files.count(); }
    }

    private static boolean hasSqlState(Throwable failure, String state) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException exception && state.equals(exception.getSQLState())) return true;
        }
        return false;
    }

    private static VoiceProfileRegistration request(boolean urgent, String version) {
        return new VoiceProfileRegistration(embedding(), 0.5, 150, version,
                urgent ? JSON.readTree("{\"urgent\":{\"samples\":[1,2]}}") : null,
                audio(), urgent ? audio() : null);
    }

    private static float[] embedding() {
        var vector = new float[192];
        vector[0] = 1;
        return vector;
    }

    private static AudioInput audio() {
        return new AudioInput("recording.wav", "audio/wav", 46, new ByteArrayInputStream(wave()));
    }

    private static byte[] wave() {
        return ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46464952).putInt(38).putInt(0x45564157)
                .putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
                .putInt(0x61746164).putInt(2).array();
    }
}
