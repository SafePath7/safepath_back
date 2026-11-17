package com.safepath.backend.guardian;

import java.time.LocalDateTime;
import java.util.Optional;

import com.safepath.backend.common.auth.CurrentUser.Actor;
import com.safepath.backend.common.exception.ApiException;
import com.safepath.backend.guardian.dto.GuardianAccess;
import com.safepath.backend.guardian.entity.GuardianRegistration;
import com.safepath.backend.guardian.entity.PermissionType;
import com.safepath.backend.guardian.entity.RegistrationStatus;
import com.safepath.backend.guardian.repository.GuardianRegistrationRepository;
import com.safepath.backend.guardian.service.GuardianRegistrationService;
import com.safepath.backend.guardian.service.GuardianRequestNotifier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
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
        when(repository.findForUpdateByUserIdAndGuardianId(OWNER, GUARDIAN)).thenReturn(Optional.empty());
        when(repository.findByUserIdAndGuardianId(OWNER, GUARDIAN)).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(eq(OWNER), eq(GUARDIAN), any())).thenReturn(0);

        assertThatThrownBy(() -> service.requestRegistration(new Actor(OWNER, false), GUARDIAN))
                .isInstanceOf(ApiException.class);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verifyNoInteractions(notifier);
    }

    @ParameterizedTest
    @EnumSource(value = RegistrationStatus.class, names = {"PENDING", "ACCEPTED", "SUSPENDED"})
    void activeOrSuspendedRegistrationIsAConflictWithoutAnInsert(RegistrationStatus status) {
        GuardianRegistration existing = registration(status, true, true);
        when(repository.findForUpdateByUserIdAndGuardianId(OWNER, GUARDIAN)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.requestRegistration(new Actor(OWNER, false), GUARDIAN))
                .isInstanceOf(ApiException.class);

        assertThat(existing.getStatus()).isEqualTo(status);
        verify(repository, never()).insertIfAbsent(anyLong(), anyLong(), any());
        verify(repository, never()).saveAndFlush(any());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verifyNoInteractions(notifier);
    }

    @ParameterizedTest
    @EnumSource(value = RegistrationStatus.class, names = {"REJECTED", "REVOKED"})
    void endedRegistrationIsReopenedAsPendingWithoutPermissionsAndNotifiesAgain(RegistrationStatus status) {
        GuardianRegistration ended = registration(status, true, true);
        ReflectionTestUtils.setField(ended, "canViewPersonalInfo", true);
        ReflectionTestUtils.setField(ended, "acceptedAt", LocalDateTime.parse("2026-11-15T00:00:00"));
        LocalDateTime previousRequest = ended.getRequestedAt();
        when(repository.findForUpdateByUserIdAndGuardianId(OWNER, GUARDIAN)).thenReturn(Optional.of(ended));
        when(repository.saveAndFlush(ended)).thenReturn(ended);

        var response = service.requestRegistration(new Actor(OWNER, false), GUARDIAN);

        assertThat(response.id()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(RegistrationStatus.PENDING);
        assertThat(ended.getAcceptedAt()).isNull();
        assertThat(ended.getRequestedAt()).isAfter(previousRequest);
        for (PermissionType type : PermissionType.values()) {
            assertThat(ended.allows(type)).isFalse();
        }
        assertThat(ended.isCanViewLocation()).isFalse();
        assertThat(ended.isCanViewStatus()).isFalse();
        assertThat(ended.isCanViewPersonalInfo()).isFalse();
        verify(repository, never()).insertIfAbsent(anyLong(), anyLong(), any());

        verifyNoInteractions(notifier);
        commit();
        verify(notifier).notifyGuardianRequested(OWNER, GUARDIAN);
    }

    @Test
    void reopenedRegistrationGivesTheGuardianNoAccessUntilAccepted() {
        GuardianRegistration revoked = registration(RegistrationStatus.REVOKED, true, true);
        when(repository.findForUpdateByUserIdAndGuardianId(OWNER, GUARDIAN)).thenReturn(Optional.of(revoked));
        when(repository.saveAndFlush(revoked)).thenReturn(revoked);
        service.requestRegistration(new Actor(OWNER, false), GUARDIAN);

        when(repository.findByUserIdAndGuardianId(OWNER, GUARDIAN)).thenReturn(Optional.of(revoked));
        assertThat(service.getGuardianAccess(OWNER, GUARDIAN)).isEqualTo(GuardianAccess.NONE);
        assertThat(service.hasGuardianPermission(OWNER, GUARDIAN, PermissionType.LOCATION)).isFalse();
        assertThat(service.hasGuardianPermission(OWNER, GUARDIAN, PermissionType.STATUS)).isFalse();
    }

    /** No row before the insert, the new row after it. */
    private void insertSucceeds() {
        when(repository.findForUpdateByUserIdAndGuardianId(OWNER, GUARDIAN)).thenReturn(Optional.empty());
        when(repository.findByUserIdAndGuardianId(OWNER, GUARDIAN)).thenReturn(Optional.of(pending()));
        when(repository.insertIfAbsent(eq(OWNER), eq(GUARDIAN), any())).thenReturn(1);
    }

    private GuardianRegistration registration(RegistrationStatus status, boolean location, boolean viewStatus) {
        return GuardianRegistrations.of(1, OWNER, GUARDIAN, status, location, viewStatus);
    }

    private void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    private GuardianRegistration pending() {
        return GuardianRegistrations.of(1, OWNER, GUARDIAN, RegistrationStatus.PENDING, false, false);
    }
}
