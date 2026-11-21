package com.safepath.backend.voice;

import com.safepath.backend.common.util.UtcTime;
import com.safepath.backend.voice.dto.VoiceProfileCommand;
import com.safepath.backend.voice.dto.VoiceProfileResult;
import com.safepath.backend.voice.exception.VoiceProfileException;
import com.safepath.backend.voice.exception.VoiceProfileException.Reason;
import java.sql.SQLException;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Caller must supply a trusted DB user ID resolved from verified Firebase identity.
 * No authentication, AI extraction or file storage is performed here. */
@Service
@Transactional(readOnly = true)
public class VoiceProfileService {
    private final VoiceProfileRepository repository;

    public VoiceProfileService(VoiceProfileRepository repository) { this.repository = repository; }

    @Transactional
    public VoiceProfileResult createVoiceProfile(Long userId, VoiceProfileCommand command) {
        validateUserId(userId);
        requireCommand(command);
        VoiceProfile profile = new VoiceProfile(userId, command.speakerEmbedding(), command.audioFileUrl(),
                command.baselineVolume(), command.baselinePitch(), command.modelVersion(),
                UtcTime.fromInstant(command.consentedAt()), command.baselineDetails(), command.urgentAudioFileUrl());
        return databaseOperation(() -> {
            if (repository.existsById(userId)) throw duplicate();
            try {
                return VoiceProfileResult.from(repository.saveAndFlush(profile));
            } catch (DataIntegrityViolationException exception) {
                // The PK remains the final guard when two requests pass existsById.
                if (hasSqlState(exception, "23505")) throw duplicate();
                throw exception;
            }
        });
    }

    public VoiceProfileResult getVoiceProfile(Long userId) {
        validateUserId(userId);
        return databaseOperation(() -> VoiceProfileResult.from(requireProfile(userId)));
    }

    @Transactional
    public VoiceProfileResult updateVoiceProfile(Long userId, VoiceProfileCommand command) {
        validateUserId(userId);
        return databaseOperation(() -> {
            VoiceProfile profile = requireProfile(userId);
            requireCommand(command);
            profile.update(command.speakerEmbedding(), command.audioFileUrl(), command.baselineVolume(),
                    command.baselinePitch(), command.modelVersion(), UtcTime.fromInstant(command.consentedAt()),
                    command.baselineDetails(), command.urgentAudioFileUrl());
            return VoiceProfileResult.from(repository.saveAndFlush(profile));
        });
    }

    /** Deletes DB metadata ONLY. Does not delete audio or implement complete account withdrawal. */
    @Transactional
    public void deleteVoiceProfile(Long userId) {
        validateUserId(userId);
        databaseOperation(() -> {
            repository.delete(requireProfile(userId));
            repository.flush();
            return null;
        });
    }

    private VoiceProfile requireProfile(Long userId) {
        return repository.findById(userId).orElseThrow(() ->
                new VoiceProfileException(Reason.NOT_FOUND, "음성 프로필이 없습니다."));
    }

    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) throw VoiceProfileException.invalid("userId");
    }

    private void requireCommand(VoiceProfileCommand command) {
        if (command == null) throw VoiceProfileException.invalid("command");
    }

    private VoiceProfileException duplicate() {
        return new VoiceProfileException(Reason.ALREADY_EXISTS, "음성 프로필이 이미 존재합니다.");
    }

    private boolean hasSqlState(Throwable exception, String sqlState) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sqlState.equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private <T> T databaseOperation(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataIntegrityViolationException exception) {
            throw new VoiceProfileException(Reason.CONSTRAINT_VIOLATION, "음성 프로필 DB 제약조건 위반입니다.", exception);
        } catch (DataAccessException exception) {
            throw new VoiceProfileException(Reason.DATABASE_ERROR, "음성 프로필 DB 작업에 실패했습니다.", exception);
        }
    }
}
