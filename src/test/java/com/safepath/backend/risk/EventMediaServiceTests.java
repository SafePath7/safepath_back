package com.safepath.backend.risk;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.safepath.backend.common.ApiException;
import com.safepath.backend.risk.RiskModels.Media;
import com.safepath.backend.risk.RiskModels.RiskEvent;
import com.safepath.backend.security.CurrentUser.Actor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventMediaServiceTests {
    @Mock RiskRepository repository;
    @Mock EventMediaStorage storage;
    @InjectMocks EventMediaService service;

    @BeforeEach
    void startSynchronization() { TransactionSynchronizationManager.initSynchronization(); }

    @AfterEach
    void clearSynchronization() { TransactionSynchronizationManager.clearSynchronization(); }

    @Test
    void deletingMetadataWithoutStorageUrlDoesNotAttemptAFileDeletion() {
        stubMedia(null);
        service.deleteEventMedia(new Actor(3L, true), 1L, 2L);
        verify(repository).deleteMedia(1L, 2L);
        verifyNoInteractions(storage);
    }

    @Test
    void fileDeletionFailureDoesNotRemoveMetadata() {
        stubMedia("key");
        var failure = new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_STORAGE_ERROR", "test");
        when(storage.deleteWithRollback("key")).thenThrow(failure);

        assertThatThrownBy(() -> service.deleteEventMedia(new Actor(3L, true), 1L, 2L)).isSameAs(failure);
        verify(repository, never()).deleteMedia(1L, 2L);
    }

    @Test
    void databaseFailureRestoresTheDeletedFileOnRollback() {
        stubMedia("key");
        var deletion = mock(EventMediaStorage.Deletion.class);
        when(storage.deleteWithRollback("key")).thenReturn(deletion);
        var failure = new IllegalStateException("database failure");
        doThrow(failure).when(repository).deleteMedia(1L, 2L);

        assertThatThrownBy(() -> service.deleteEventMedia(new Actor(3L, true), 1L, 2L)).isSameAs(failure);
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(deletion).restore();
    }

    @Test
    void successfulCommitDoesNotRestoreTheDeletedFile() {
        stubMedia("key");
        var deletion = mock(EventMediaStorage.Deletion.class);
        when(storage.deleteWithRollback("key")).thenReturn(deletion);

        service.deleteEventMedia(new Actor(3L, true), 1L, 2L);
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        var order = inOrder(storage, repository);
        order.verify(storage).deleteWithRollback("key");
        order.verify(repository).deleteMedia(1L, 2L);
        verifyNoInteractions(deletion);
    }

    @Test
    void rollbackRestoresBeforeCleaningUpAnUploadInTheSameTransaction() {
        stubMedia("key");
        var file = new MockMultipartFile("file", new byte[] {1});
        var deletion = mock(EventMediaStorage.Deletion.class);
        when(storage.store(file)).thenReturn(new EventMediaStorage.StoredFile("key", "file"));
        when(storage.deleteWithRollback("key")).thenReturn(deletion);

        service.uploadEventMedia(new Actor(3L, true), 1L, file, "AUDIO");
        service.deleteEventMedia(new Actor(3L, true), 1L, 2L);
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        var order = inOrder(deletion, storage);
        order.verify(deletion).restore();
        order.verify(storage).delete("key");
    }

    private void stubMedia(String storageKey) {
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        when(repository.event(1L, true)).thenReturn(Optional.of(
                new RiskEvent(1L, UUID.randomUUID(), "SOS", "test", "PENDING", now, now, now)));
        when(repository.media(1L, 2L)).thenReturn(Optional.of(
                new Media(2L, 1L, "AUDIO", null, storageKey, null, now, now)));
    }
}
