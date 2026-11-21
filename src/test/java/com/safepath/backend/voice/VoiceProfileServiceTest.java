package com.safepath.backend.voice;

import com.safepath.backend.voice.dto.VoiceProfileCommand;
import com.safepath.backend.voice.dto.VoiceProfileResult;
import com.safepath.backend.voice.exception.VoiceProfileException;
import com.safepath.backend.voice.exception.VoiceProfileException.Reason;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class VoiceProfileServiceTest {
    private final VoiceProfileRepository repository = mock(VoiceProfileRepository.class);
    private final VoiceProfileService service = new VoiceProfileService(repository);
    private static final Instant CONSENT = Instant.parse("2026-11-17T00:00:00.123456Z");

    @BeforeEach
    void saveReturnsTheEntity() {
        lenient().when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    static VoiceProfileCommand command() {
        return new VoiceProfileCommand(new float[]{0.1f, -0.2f, 0.3f}, "internal-audio-reference",
                -12.5, 180, "model-test", CONSENT);
    }

    private VoiceProfile existing() {
        VoiceProfile profile = new VoiceProfile(7L, new float[]{1, 2}, "old-audio-reference",
                2, 3, "old-model", LocalDateTime.of(2026, 1, 1, 0, 0));
        ReflectionTestUtils.setField(profile, "createdAt", LocalDateTime.of(2026, 1, 1, 0, 0));
        ReflectionTestUtils.setField(profile, "updatedAt", LocalDateTime.of(2026, 1, 2, 0, 0));
        return profile;
    }

    @Test
    void createsValidatedInternalSnapshot() {
        var result = service.createVoiceProfile(7L, command());
        assertEquals(7L, result.userId());
        assertArrayEquals(command().speakerEmbedding(), result.speakerEmbedding());
        assertEquals(CONSENT, result.consentedAt());
        assertNotNull(result.createdAt());
        assertEquals(result.createdAt(), result.updatedAt());
        verify(repository).existsById(7L);
        verify(repository).saveAndFlush(argThat(profile -> profile.isNew() && profile.getId().equals(7L)));
    }

    @Test
    void rejectsExistingProfileWithoutSaving() {
        when(repository.existsById(7L)).thenReturn(true);
        assertReason(Reason.ALREADY_EXISTS, () -> service.createVoiceProfile(7L, command()));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentPrimaryKeyConflictIsReportedAsDuplicate() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("constraint",
                new SQLException("duplicate", "23505")));
        assertReason(Reason.ALREADY_EXISTS, () -> service.createVoiceProfile(7L, command()));
    }

    @Test
    void foreignKeyFailureIsNotMisreportedAsDuplicate() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("constraint",
                new SQLException("foreign key", "23503")));
        assertReason(Reason.CONSTRAINT_VIOLATION, () -> service.createVoiceProfile(7L, command()));
    }

    @Test
    void unexpectedDatabaseFailureIsDistinguished() {
        when(repository.findById(7L)).thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertReason(Reason.DATABASE_ERROR, () -> service.getVoiceProfile(7L));
    }

    @Test
    void returnsExistingProfileWithoutAnEntityReference() {
        VoiceProfile profile = existing();
        when(repository.findById(7L)).thenReturn(Optional.of(profile));
        VoiceProfileResult result = service.getVoiceProfile(7L);
        float[] external = result.speakerEmbedding();
        external[0] = 999;
        assertArrayEquals(new float[]{1, 2}, profile.getSpeakerEmbedding());
        assertArrayEquals(new float[]{1, 2}, result.speakerEmbedding());
    }

    @Test
    void updatesFieldsAndTimestampWithoutChangingIdentityOrCreationTime() {
        VoiceProfile profile = existing();
        var createdAt = profile.getCreatedAt();
        var previousUpdate = profile.getUpdatedAt();
        when(repository.findById(7L)).thenReturn(Optional.of(profile));
        var result = service.updateVoiceProfile(7L, command());
        assertEquals(7L, profile.getId());
        assertEquals(createdAt, profile.getCreatedAt());
        assertTrue(profile.getUpdatedAt().isAfter(previousUpdate));
        assertArrayEquals(command().speakerEmbedding(), result.speakerEmbedding());
        assertEquals(command().audioFileUrl(), result.audioFileUrl());
        assertEquals(command().baselineVolume(), result.baselineVolume());
        assertEquals(command().baselinePitch(), result.baselinePitch());
        assertEquals(command().modelVersion(), result.modelVersion());
        verify(repository).saveAndFlush(profile);
    }

    @Test
    void deletesOnlyTheLoadedDatabaseRecord() {
        VoiceProfile profile = existing();
        when(repository.findById(7L)).thenReturn(Optional.of(profile));
        service.deleteVoiceProfile(7L);
        verify(repository).findById(7L);
        verify(repository).delete(profile);
        verify(repository).flush();
        verifyNoMoreInteractions(repository);
    }

    @Test
    void allOperationsOnMissingProfilesFailClearly() {
        assertReason(Reason.NOT_FOUND, () -> service.getVoiceProfile(7L));
        assertReason(Reason.NOT_FOUND, () -> service.updateVoiceProfile(7L, command()));
        assertReason(Reason.NOT_FOUND, () -> service.deleteVoiceProfile(7L));
        verify(repository, never()).delete(any());
        verify(repository, never()).saveAndFlush(any());
    }

    static Stream<Arguments> invalidCommands() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(new VoiceProfileCommand(null, "audio", 0, 0, "v", CONSENT)),
                Arguments.of(new VoiceProfileCommand(new float[0], "audio", 0, 0, "v", CONSENT)),
                Arguments.of(new VoiceProfileCommand(new float[]{Float.NaN}, "audio", 0, 0, "v", CONSENT)),
                Arguments.of(new VoiceProfileCommand(new float[]{Float.POSITIVE_INFINITY}, "audio", 0, 0, "v", CONSENT)),
                Arguments.of(new VoiceProfileCommand(new float[]{Float.NEGATIVE_INFINITY}, "audio", 0, 0, "v", CONSENT)),
                Arguments.of(new VoiceProfileCommand(new float[]{1}, null, 0, 0, "v", CONSENT)),
                Arguments.of(new VoiceProfileCommand(new float[]{1}, " \t", 0, 0, "v", CONSENT)),
                Arguments.of(new VoiceProfileCommand(new float[]{1}, "audio", Double.NaN, 0, "v", CONSENT)),
                Arguments.of(new VoiceProfileCommand(new float[]{1}, "audio", Double.POSITIVE_INFINITY, 0, "v", CONSENT)),
                Arguments.of(new VoiceProfileCommand(new float[]{1}, "audio", Double.NEGATIVE_INFINITY, 0, "v", CONSENT)),
                Arguments.of(new VoiceProfileCommand(new float[]{1}, "audio", 0, Double.NaN, "v", CONSENT)),
                Arguments.of(new VoiceProfileCommand(new float[]{1}, "audio", 0, Double.POSITIVE_INFINITY, "v", CONSENT)),
                Arguments.of(new VoiceProfileCommand(new float[]{1}, "audio", 0, Double.NEGATIVE_INFINITY, "v", CONSENT)),
                Arguments.of(new VoiceProfileCommand(new float[]{1}, "audio", 0, 0, null, CONSENT)),
                Arguments.of(new VoiceProfileCommand(new float[]{1}, "audio", 0, 0, " ", CONSENT)),
                Arguments.of(new VoiceProfileCommand(new float[]{1}, "audio", 0, 0, "v".repeat(51), CONSENT)),
                Arguments.of(new VoiceProfileCommand(new float[]{1}, "audio", 0, 0, "v", null)));
    }

    @ParameterizedTest
    @MethodSource("invalidCommands")
    void rejectsInvalidCreateBeforeAnyDatabaseAccess(VoiceProfileCommand command) {
        assertReason(Reason.INVALID_INPUT, () -> service.createVoiceProfile(7L, command));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @MethodSource("invalidCommands")
    void invalidUpdateLeavesExistingEntityUnchanged(VoiceProfileCommand command) {
        VoiceProfile profile = existing();
        var before = VoiceProfileResult.from(profile);
        when(repository.findById(7L)).thenReturn(Optional.of(profile));
        assertReason(Reason.INVALID_INPUT, () -> service.updateVoiceProfile(7L, command));
        assertArrayEquals(before.speakerEmbedding(), profile.getSpeakerEmbedding());
        assertEquals(before.audioFileUrl(), profile.getAudioFileUrl());
        assertEquals(before.updatedAt(), VoiceProfileResult.from(profile).updatedAt());
        verify(repository, never()).saveAndFlush(any());
    }

    static Stream<Arguments> invalidIds() {
        return Stream.of(Arguments.of((Object) null), Arguments.of(0L), Arguments.of(-1L));
    }

    @ParameterizedTest
    @MethodSource("invalidIds")
    void rejectsInvalidTrustedUserIdForEveryOperation(Long userId) {
        assertReason(Reason.INVALID_INPUT, () -> service.createVoiceProfile(userId, command()));
        assertReason(Reason.INVALID_INPUT, () -> service.getVoiceProfile(userId));
        assertReason(Reason.INVALID_INPUT, () -> service.updateVoiceProfile(userId, command()));
        assertReason(Reason.INVALID_INPUT, () -> service.deleteVoiceProfile(userId));
        verifyNoInteractions(repository);
    }

    @Test
    void copiesEmbeddingOnInputAndEntityBoundariesAndRedactsToString() {
        float[] embedding = {1, 2};
        var command = new VoiceProfileCommand(embedding, "private-audio-url", -1, -2, "v".repeat(50), CONSENT);
        embedding[0] = 99;
        command.speakerEmbedding()[0] = 88;
        var result = service.createVoiceProfile(7L, command);
        assertArrayEquals(new float[]{1, 2}, result.speakerEmbedding());
        assertFalse(command.toString().contains("private-audio-url"));
        assertFalse(result.toString().contains("private-audio-url"));
        VoiceProfile profile = existing();
        profile.getSpeakerEmbedding()[0] = 99;
        assertArrayEquals(new float[]{1, 2}, profile.getSpeakerEmbedding());
    }

    @Test
    void databaseErrorsAreWrappedForUpdateDeleteAndCreateLookup() {
        when(repository.existsById(7L)).thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertReason(Reason.DATABASE_ERROR, () -> service.createVoiceProfile(7L, command()));
        when(repository.findById(7L)).thenReturn(Optional.of(existing()));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("constraint"));
        assertReason(Reason.CONSTRAINT_VIOLATION, () -> service.updateVoiceProfile(7L, command()));
        doThrow(new DataAccessResourceFailureException("unavailable")).when(repository).flush();
        assertReason(Reason.DATABASE_ERROR, () -> service.deleteVoiceProfile(7L));
    }

    private void assertReason(Reason reason, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(reason, assertThrows(VoiceProfileException.class, operation).reason());
    }
}
