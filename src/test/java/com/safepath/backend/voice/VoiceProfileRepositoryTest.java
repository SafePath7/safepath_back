package com.safepath.backend.voice;

import com.safepath.backend.common.util.UtcTime;
import com.safepath.backend.voice.dto.VoiceProfileCommand;
import com.safepath.backend.voice.exception.VoiceProfileException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in local PostgreSQL only; no application startup, Flyway, Firebase, or S3.
 * All synthetic rows use explicit IDs and roll back, without changing sequences. */
@EnabledIfEnvironmentVariable(named = "SAFEPATH_VOICE_TEST_DB_URL",
        matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):5433/safepath")
class VoiceProfileRepositoryTest {
    private static AnnotationConfigApplicationContext context;
    private static VoiceProfileRepository repository;
    private static JdbcTemplate jdbc;
    private static EntityManager entityManager;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void connectToExplicitLocalDatabase() {
        context = new AnnotationConfigApplicationContext(PersistenceConfig.class);
        repository = context.getBean(VoiceProfileRepository.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterAll
    static void close() { if (context != null) context.close(); }

    @Test
    void realArrayAndEveryFieldRoundTripAndDelete() {
        rollback(userId -> {
            VoiceProfile profile = profile(userId);
            assertTrue(profile.isNew());
            repository.saveAndFlush(profile);
            assertFalse(profile.isNew());
            entityManager.clear();
            VoiceProfile loaded = repository.findById(userId).orElseThrow();
            assertFalse(loaded.isNew());
            assertArrayEquals(profile.getSpeakerEmbedding(), loaded.getSpeakerEmbedding());
            assertEquals("real[]", jdbc.queryForObject(
                    "SELECT pg_typeof(speaker_embedding)::text FROM voice_profiles WHERE user_id = ?", String.class, userId));
            assertEquals(profile.getAudioFileUrl(), loaded.getAudioFileUrl());
            assertEquals(profile.getBaselineVolume(), loaded.getBaselineVolume());
            assertEquals(profile.getBaselinePitch(), loaded.getBaselinePitch());
            assertEquals(profile.getModelVersion(), loaded.getModelVersion());
            assertEquals(profile.getConsentedAt(), loaded.getConsentedAt());
            assertEquals(profile.getCreatedAt(), loaded.getCreatedAt());
            assertEquals(profile.getUpdatedAt(), loaded.getUpdatedAt());
            assertTrue(repository.existsById(userId));
            repository.delete(loaded);
            repository.flush();
            entityManager.clear();
            assertFalse(repository.existsById(userId));
        });
    }

    @Test
    void updatePersistsTimestampAndKeepsCreatedAtAndUserId() {
        rollback(userId -> {
            VoiceProfile profile = repository.saveAndFlush(profile(userId));
            var createdAt = profile.getCreatedAt();
            jdbc.update("UPDATE voice_profiles SET updated_at = TIMESTAMP '2000-01-01' WHERE user_id = ?", userId);
            entityManager.clear();
            VoiceProfile loaded = repository.findById(userId).orElseThrow();
            loaded.update(new float[]{4, 5, 6, 7}, "replacement-test-reference", -1, 250, "next-model",
                    LocalDateTime.of(2026, 2, 2, 0, 0));
            repository.saveAndFlush(loaded);
            var updatedAt = loaded.getUpdatedAt();
            entityManager.clear();
            loaded = repository.findById(userId).orElseThrow();
            assertEquals(userId, loaded.getUserId());
            assertEquals(createdAt, loaded.getCreatedAt());
            assertEquals(updatedAt, loaded.getUpdatedAt());
            assertTrue(updatedAt.isAfter(LocalDateTime.of(2000, 1, 1, 0, 0)));
            assertArrayEquals(new float[]{4, 5, 6, 7}, loaded.getSpeakerEmbedding());
        });
    }

    @Test
    void featuresReadPreservesNestedJsonSeparatesUsersAndLeavesDatabaseUnchanged() {
        rollback(userId -> {
            var features = new VoiceProfileFeatureService(new VoiceProfileService(repository));
            long otherId = insertFixtureUser();
            var mapper = new JsonMapper();
            var details = mapper.createObjectNode().set("urgent", mapper.createObjectNode()
                    .set("samples", mapper.createArrayNode().add(1).add(2)));
            var embedding = new float[192];
            embedding[0] = 1;
            var consent = LocalDateTime.of(2026, 1, 1, 0, 0);
            repository.saveAndFlush(new VoiceProfile(userId, embedding, "private-calm-reference", -20,
                    140, "first-model", consent, details, "private-urgent-reference"));
            repository.saveAndFlush(new VoiceProfile(otherId, new float[]{0.25f}, "private-other-reference", -35,
                    180, "legacy-model", consent));
            entityManager.clear();
            String snapshotQuery = "SELECT to_jsonb(profile)::text FROM voice_profiles profile WHERE user_id = ?";
            String before = jdbc.queryForObject(snapshotQuery, String.class, userId);

            var first = features.getFeatures(userId);
            var second = features.getFeatures(otherId);
            assertArrayEquals(embedding, first.speakerEmbedding());
            assertEquals(-20, first.baselineVolume());
            assertEquals(140, first.baselinePitch());
            assertEquals("first-model", first.modelVersion());
            assertEquals(details, first.baselineDetails());
            assertArrayEquals(new float[]{0.25f}, second.speakerEmbedding());
            assertEquals("legacy-model", second.modelVersion());
            assertNull(second.baselineDetails());
            first.speakerEmbedding()[0] = 0;
            ((tools.jackson.databind.node.ObjectNode) first.baselineDetails()).remove("urgent");
            assertEquals(1, first.speakerEmbedding()[0]);
            assertEquals(details, first.baselineDetails());
            var serialized = mapper.valueToTree(first);
            assertEquals(5, serialized.size());
            assertFalse(serialized.has("audioFileUrl"));
            assertFalse(serialized.has("urgentAudioFileUrl"));
            assertFalse(first.toString().contains("first-model"));
            assertEquals(before, jdbc.queryForObject(snapshotQuery, String.class, userId));
        });
    }

    @Test
    void featuresDistinguishUnregisteredProfileAndInvalidUser() {
        rollback(userId -> {
            var features = new VoiceProfileFeatureService(new VoiceProfileService(repository));
            assertEquals(VoiceProfileException.Reason.NOT_FOUND,
                    assertThrows(VoiceProfileException.class, () -> features.getFeatures(userId)).reason());
            assertEquals(VoiceProfileException.Reason.INVALID_INPUT,
                    assertThrows(VoiceProfileException.class, () -> features.getFeatures(0L)).reason());
        });
    }

    @Test
    void legacyInsertReadsNewColumnsAsSqlNull() {
        rollback(userId -> {
            jdbc.update("""
                    INSERT INTO voice_profiles (user_id, speaker_embedding, audio_file_url,
                        baseline_volume, baseline_pitch, model_version, consented_at)
                    VALUES (?, ARRAY[0.125]::real[], 'legacy-reference', 0.5, 150, 'legacy-model',
                        TIMESTAMP '2026-01-01')
                    """, userId);

            var loaded = repository.findById(userId).orElseThrow();
            assertNull(loaded.getBaselineDetails());
            assertNull(loaded.getUrgentAudioFileUrl());
            assertEquals("legacy-reference", loaded.getAudioFileUrl());
        });
    }

    @Test
    void analysisAndUrgentReferenceRoundTripAndCanBeCleared() {
        rollback(userId -> {
            var service = new VoiceProfileService(repository);
            var mapper = new JsonMapper();
            var initialDetails = mapper.readTree("""
                    {"embedding_source":"calm","urgent":{"samples":[1,2],"usable":true}}
                    """);
            var replacementDetails = mapper.readTree("{\"model_extension\":{\"nullable\":null}}");
            var consent = UtcTime.toInstant(LocalDateTime.of(2026, 1, 1, 0, 0));
            service.createVoiceProfile(userId, new VoiceProfileCommand(new float[]{0.125f}, "calm", 0.5,
                    150, "test-model", consent, initialDetails, "urgent"));
            entityManager.clear();
            var created = service.getVoiceProfile(userId);
            assertEquals(initialDetails, created.baselineDetails());
            assertEquals("urgent", created.urgentAudioFileUrl());
            assertEquals("jsonb", jdbc.queryForObject(
                    "SELECT pg_typeof(baseline_details)::text FROM voice_profiles WHERE user_id = ?",
                    String.class, userId));
            jdbc.update("UPDATE voice_profiles SET updated_at = TIMESTAMP '2000-01-01' WHERE user_id = ?", userId);
            entityManager.clear();

            service.updateVoiceProfile(userId, new VoiceProfileCommand(new float[]{0.25f}, "new-calm", 0.6,
                    155, "next-model", consent, replacementDetails, null));
            entityManager.clear();
            var updated = service.getVoiceProfile(userId);
            assertEquals(replacementDetails, updated.baselineDetails());
            assertNull(updated.urgentAudioFileUrl());
            assertEquals("new-calm", updated.audioFileUrl());
            assertEquals(created.createdAt(), updated.createdAt());
            assertTrue(updated.updatedAt().isAfter(UtcTime.toInstant(LocalDateTime.of(2000, 1, 1, 0, 0))));

            service.updateVoiceProfile(userId, new VoiceProfileCommand(new float[]{0.25f}, "new-calm", 0.6,
                    155, "next-model", consent));
            entityManager.clear();
            assertNull(service.getVoiceProfile(userId).baselineDetails());
            assertTrue(jdbc.queryForObject(
                    "SELECT baseline_details IS NULL AND urgent_audio_file_url IS NULL FROM voice_profiles WHERE user_id = ?",
                    Boolean.class, userId));
            service.deleteVoiceProfile(userId);
            assertFalse(repository.existsById(userId));
        });
    }

    @Test
    void databaseRejectedUpdatePreservesAllPreviouslyStoredValues() {
        rollback(userId -> {
            var service = new VoiceProfileService(repository);
            repository.saveAndFlush(profile(userId));
            entityManager.clear();
            var before = service.getVoiceProfile(userId);
            jdbc.execute("SAVEPOINT before_voice_update");

            // PostgreSQL rejects NUL in text even though this value passes domain validation.
            var replacement = new VoiceProfileCommand(new float[]{4, 5}, "invalid\0reference",
                    1.0, 200, "replacement-model", UtcTime.toInstant(LocalDateTime.of(2026, 2, 2, 0, 0)));
            var failure = assertThrows(VoiceProfileException.class,
                    () -> service.updateVoiceProfile(userId, replacement));
            assertEquals(VoiceProfileException.Reason.CONSTRAINT_VIOLATION, failure.reason());

            jdbc.execute("ROLLBACK TO SAVEPOINT before_voice_update");
            entityManager.clear();
            var after = service.getVoiceProfile(userId);
            assertArrayEquals(before.speakerEmbedding(), after.speakerEmbedding());
            assertEquals(before.userId(), after.userId());
            assertEquals(before.audioFileUrl(), after.audioFileUrl());
            assertEquals(before.baselineVolume(), after.baselineVolume());
            assertEquals(before.baselinePitch(), after.baselinePitch());
            assertEquals(before.modelVersion(), after.modelVersion());
            assertEquals(before.consentedAt(), after.consentedAt());
            assertEquals(before.createdAt(), after.createdAt());
            assertEquals(before.updatedAt(), after.updatedAt());
        });
    }

    @Test
    void secondAssignedIdInsertCannotOverwriteExistingProfile() {
        rollback(userId -> {
            repository.saveAndFlush(profile(userId));
            entityManager.clear();
            assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(profile(userId)));
        });
    }

    @Test
    void rejectsUnknownUserViaTheActualForeignKey() {
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            long unknownUser = ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 2_000_000_000_000L);
            assertFalse(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE id = ?)", Boolean.class, unknownUser));
            assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(profile(unknownUser)));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"speaker_embedding", "audio_file_url", "baseline_volume", "baseline_pitch",
            "model_version", "consented_at"})
    void databaseRejectsNullForEachRequiredPayloadColumn(String column) {
        rollback(userId -> {
            repository.saveAndFlush(profile(userId));
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE voice_profiles SET " + column + " = NULL WHERE user_id = ?", userId));
        });
    }

    private static VoiceProfile profile(Long userId) {
        return new VoiceProfile(userId, new float[]{0.125f, -0.25f, Float.MIN_NORMAL}, "synthetic-test-reference",
                0.5, 150, "test-model", LocalDateTime.of(2026, 1, 1, 0, 0, 0, 123456000));
    }

    private void rollback(Consumer<Long> test) {
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            test.accept(insertFixtureUser());
        });
    }

    private long insertFixtureUser() {
        long userId = ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 2_000_000_000_000L);
        String marker = "voice-test-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, firebase_uid, name, email, phone_hash, phone_encrypted)
                VALUES (?, ?, 'voice repository test', ?, ?, 'synthetic-test-value')
                """, userId, marker, marker + "@example.test", marker);
        return userId;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = VoiceProfileRepository.class)
    static class PersistenceConfig {
        @Bean
        DataSource dataSource() {
            var source = new DriverManagerDataSource();
            source.setUrl(required("SAFEPATH_VOICE_TEST_DB_URL"));
            source.setUsername(required("SAFEPATH_VOICE_TEST_DB_USERNAME"));
            source.setPassword(required("SAFEPATH_VOICE_TEST_DB_PASSWORD"));
            return source;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.safepath.backend.voice");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.show_sql", "false"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory, DataSource dataSource) {
            var manager = new JpaTransactionManager(factory);
            manager.setDataSource(dataSource);
            return manager;
        }

        private String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) throw new IllegalStateException("Required test variable is missing: " + name);
            return value;
        }
    }
}
