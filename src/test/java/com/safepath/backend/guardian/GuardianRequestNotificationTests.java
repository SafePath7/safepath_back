package com.safepath.backend.guardian;

import java.time.Instant;
import java.util.Optional;

import com.safepath.backend.guardian.GuardianModels.Registration;
import com.safepath.backend.guardian.GuardianModels.Status;
import com.safepath.backend.guardianrisk.ApiException;
import com.safepath.backend.guardianrisk.CurrentUser.Actor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class GuardianRequestNotificationTests {
    private static final long OWNER = 10;
    private static final long GUARDIAN = 20;
    private final GuardianRepository repository = mock(GuardianRepository.class);
    private final GuardianRequestNotifier notifier = mock(GuardianRequestNotifier.class);
    private final GuardianRegistrationService service = new GuardianRegistrationService(repository, notifier);

    @BeforeEach
    void startTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
        when(repository.userExists(anyLong())).thenReturn(true);
        when(repository.findRegistration(OWNER, GUARDIAN)).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void guardianIsNotifiedOnlyAfterTheRequestCommits() {
        when(repository.request(OWNER, GUARDIAN)).thenReturn(Optional.of(pending()));

        assertThat(service.requestRegistration(new Actor(OWNER, false), GUARDIAN)).isEqualTo(pending());
        verifyNoInteractions(notifier);

        commit();
        verify(notifier).notifyGuardianRequested(OWNER, GUARDIAN);
    }

    @Test
    void notificationFailureDoesNotFailTheSavedRequest() {
        when(repository.request(OWNER, GUARDIAN)).thenReturn(Optional.of(pending()));
        doThrow(new IllegalStateException("FirebaseApp is not initialized"))
                .when(notifier).notifyGuardianRequested(OWNER, GUARDIAN);

        service.requestRegistration(new Actor(OWNER, false), GUARDIAN);
        commit();

        verify(notifier).notifyGuardianRequested(OWNER, GUARDIAN);
    }

    @Test
    void rejectedRequestRegistersNoNotification() {
        when(repository.request(OWNER, GUARDIAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestRegistration(new Actor(OWNER, false), GUARDIAN))
                .isInstanceOf(ApiException.class);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verifyNoInteractions(notifier);
    }

    private void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    private Registration pending() {
        Instant now = Instant.parse("2026-11-14T00:00:00Z");
        return new Registration(1, OWNER, GUARDIAN, Status.PENDING, now, null, false, false, false, now, now);
    }
}
