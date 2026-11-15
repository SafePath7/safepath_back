package com.safepath.backend.guardian;

import java.util.Optional;

import com.safepath.backend.common.auth.CurrentUser.Actor;
import com.safepath.backend.common.exception.ApiException;
import com.safepath.backend.guardian.entity.GuardianRegistration;
import com.safepath.backend.guardian.entity.RegistrationStatus;
import com.safepath.backend.guardian.repository.GuardianRegistrationRepository;
import com.safepath.backend.guardian.service.GuardianRegistrationService;
import com.safepath.backend.guardian.service.GuardianRequestNotifier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GuardianRequestNotificationTests {
    private static final long OWNER = 10;
    private static final long GUARDIAN = 20;
    private final GuardianRegistrationRepository repository = mock(GuardianRegistrationRepository.class);
    private final GuardianRequestNotifier notifier = mock(GuardianRequestNotifier.class);
    private final GuardianRegistrationService service = new GuardianRegistrationService(repository, notifier);

    @BeforeEach
    void startTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
        when(repository.memberExists(anyLong())).thenReturn(true);
    }

    @AfterEach
    void clearTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void guardianIsNotifiedOnlyAfterTheRequestCommits() {
        insertSucceeds();

        var response = service.requestRegistration(new Actor(OWNER, false), GUARDIAN);
        assertThat(response.id()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(RegistrationStatus.PENDING);
        verifyNoInteractions(notifier);

        commit();
        verify(notifier).notifyGuardianRequested(OWNER, GUARDIAN);
    }

    @Test
    void notificationFailureDoesNotFailTheSavedRequest() {
        insertSucceeds();
        doThrow(new IllegalStateException("FirebaseApp is not initialized"))
                .when(notifier).notifyGuardianRequested(OWNER, GUARDIAN);

        service.requestRegistration(new Actor(OWNER, false), GUARDIAN);
        commit();

        verify(notifier).notifyGuardianRequested(OWNER, GUARDIAN);
    }

    @Test
    void requestLosingTheInsertRaceRegistersNoNotification() {
        when(repository.findByUserIdAndGuardianId(OWNER, GUARDIAN)).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(eq(OWNER), eq(GUARDIAN), any())).thenReturn(0);

        assertThatThrownBy(() -> service.requestRegistration(new Actor(OWNER, false), GUARDIAN))
                .isInstanceOf(ApiException.class);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verifyNoInteractions(notifier);
    }

    @Test
    void existingRegistrationIsAConflictWithoutAnInsert() {
        when(repository.findByUserIdAndGuardianId(OWNER, GUARDIAN)).thenReturn(Optional.of(pending()));

        assertThatThrownBy(() -> service.requestRegistration(new Actor(OWNER, false), GUARDIAN))
                .isInstanceOf(ApiException.class);

        verify(repository, never()).insertIfAbsent(anyLong(), anyLong(), any());
        verifyNoInteractions(notifier);
    }

    /** No row before the insert, the new row after it. */
    private void insertSucceeds() {
        when(repository.findByUserIdAndGuardianId(OWNER, GUARDIAN))
                .thenReturn(Optional.empty(), Optional.of(pending()));
        when(repository.insertIfAbsent(eq(OWNER), eq(GUARDIAN), any())).thenReturn(1);
    }

    private void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    private GuardianRegistration pending() {
        return GuardianRegistrations.of(1, OWNER, GUARDIAN, RegistrationStatus.PENDING, false, false);
    }
}
