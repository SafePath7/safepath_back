package com.safepath.backend.voice;

import com.safepath.backend.voice.dto.VoiceProfileCommand;
import com.safepath.backend.voice.dto.VoiceProfileRegistration;
import com.safepath.backend.voice.dto.VoiceProfileRegistration.AudioInput;
import com.safepath.backend.voice.dto.VoiceProfileResult;
import com.safepath.backend.voice.exception.VoiceProfileException;
import com.safepath.backend.voice.exception.VoiceProfileException.Reason;
import com.safepath.backend.voice.storage.VoiceAudioStorage;
import com.safepath.backend.voice.storage.VoiceAudioStorageException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.safepath.backend.voice.storage.VoiceAudioStorageException.Reason.STORAGE_DISABLED;

/** Internal file lifecycle boundary. The caller must supply a verified member ID and obtain consent. */
@Service
public class VoiceProfileRegistrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoiceProfileRegistrationService.class);

    private final VoiceProfileRepository repository;
    private final VoiceProfileService profiles;
    private final ObjectProvider<VoiceAudioStorage> storageProvider;
    private final TransactionTemplate transaction;
    private final Set<Cleanup> pendingCleanup = ConcurrentHashMap.newKeySet();

    public VoiceProfileRegistrationService(VoiceProfileRepository repository, VoiceProfileService profiles,
            ObjectProvider<VoiceAudioStorage> storageProvider, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.profiles = profiles;
        this.storageProvider = storageProvider;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public VoiceProfileResult register(Long userId, VoiceProfileRegistration registration) {
        if (registration == null) throw VoiceProfileException.invalid("registration");
        return change(userId, changes -> {
            var prior = repository.findById(userId);
            prior.ifPresent(changes::rememberPrevious);
            Instant consentedAt = Instant.now();
            String calmReference = changes.save(registration.calmAudio());
            String urgentReference = registration.urgentAudio() == null ? null : changes.save(registration.urgentAudio());
            var command = new VoiceProfileCommand(registration.speakerEmbedding(), calmReference,
                    registration.baselineVolume(), registration.baselinePitch(), registration.modelVersion(),
                    consentedAt, registration.baselineDetails(), urgentReference);
            return prior.isPresent() ? profiles.updateVoiceProfile(userId, command)
                    : profiles.createVoiceProfile(userId, command);
        });
    }

    public void delete(Long userId) {
        change(userId, changes -> {
            repository.findById(userId).ifPresent(changes::rememberPrevious);
            profiles.deleteVoiceProfile(userId);
            return null;
        });
    }

    /** Retries work retained by this process and returns the number still pending. */
    public int retryPendingCleanup() {
        requireNoAmbientTransaction();
        VoiceAudioStorage storage = requireStorage();
        for (Cleanup cleanup : Set.copyOf(pendingCleanup)) attemptCleanup(storage, cleanup);
        return pendingCleanup.size();
    }

    private <T> T change(Long userId, Function<FileChanges, T> action) {
        requireNoAmbientTransaction();
        if (userId == null || userId <= 0) throw VoiceProfileException.invalid("userId");
        VoiceAudioStorage storage = requireStorage();
        var changes = new FileChanges(storage);
        try {
            T result = transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(changes);
                if (repository.lockOwner(userId).isEmpty()) {
                    throw new VoiceProfileException(Reason.NOT_FOUND, "Voice profile member was not found.");
                }
                return action.apply(changes);
            });
            if (changes.outcome != TransactionSynchronization.STATUS_COMMITTED) {
                throw new VoiceProfileException(Reason.DATABASE_ERROR, "Voice profile transaction did not commit.");
            }
            return result;
        } finally {
            switch (changes.outcome) {
                case TransactionSynchronization.STATUS_COMMITTED -> clean(storage, userId, changes.previous);
                case TransactionSynchronization.STATUS_ROLLED_BACK -> clean(storage, userId, changes.created);
                // Reconcile uncertain commits in a fresh, owner-locked transaction.
                default -> {
                    clean(storage, userId, changes.previous);
                    clean(storage, userId, changes.created);
                }
            }
        }
    }

    private void clean(VoiceAudioStorage storage, long userId, Set<String> references) {
        for (String reference : references) {
            if (VoiceAudioStorage.isManagedReference(reference)) {
                attemptCleanup(storage, new Cleanup(userId, reference));
            }
        }
    }

    private void attemptCleanup(VoiceAudioStorage storage, Cleanup cleanup) {
        try {
            transaction.executeWithoutResult(status -> {
                repository.lockOwner(cleanup.userId());
                if (!repository.existsByAudioFileUrlOrUrgentAudioFileUrl(cleanup.reference(), cleanup.reference())) {
                    storage.delete(cleanup.reference());
                }
            });
            pendingCleanup.remove(cleanup);
        } catch (RuntimeException failure) {
            pendingCleanup.add(cleanup);
            LOGGER.warn("Voice audio cleanup deferred for member {}.", cleanup.userId());
        }
    }

    private static void requireNoAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Voice profile lifecycle operations require an independent transaction boundary.");
        }
    }

    private VoiceAudioStorage requireStorage() {
        VoiceAudioStorage storage = storageProvider.getIfAvailable();
        if (storage == null) throw new VoiceAudioStorageException(STORAGE_DISABLED);
        return storage;
    }

    private record Cleanup(long userId, String reference) {}

    private static final class FileChanges implements TransactionSynchronization {
        private final VoiceAudioStorage storage;
        private final Set<String> created = new LinkedHashSet<>();
        private final Set<String> previous = new LinkedHashSet<>();
        private int outcome = STATUS_UNKNOWN;

        private FileChanges(VoiceAudioStorage storage) { this.storage = storage; }

        private String save(AudioInput audio) {
            String reference = storage.store(audio.fileName(), audio.contentType(), audio.declaredSize(), audio.contents());
            created.add(reference);
            return reference;
        }

        private void rememberPrevious(VoiceProfile profile) {
            previous.add(profile.getAudioFileUrl());
            previous.add(profile.getUrgentAudioFileUrl());
        }

        @Override public void afterCompletion(int status) { outcome = status; }
    }
}
