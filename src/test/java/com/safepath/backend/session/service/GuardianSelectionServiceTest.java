package com.safepath.backend.session.service;

import com.safepath.backend.guardian.service.GuardianRegistrationService;
import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

class GuardianSelectionServiceTest {
    private final ActiveSessionStore sessions = new ActiveSessionStore();
    private final SelectedGuardianStore selected = new SelectedGuardianStore(sessions);
    private final GuardianRegistrationService permissions = mock(GuardianRegistrationService.class);
    private final GuardianSelectionService selection = new GuardianSelectionService(sessions, selected);
    private final SafeReturnSessionService lifecycle = new SafeReturnSessionService(sessions, selected, new com.safepath.backend.session.store.SessionChannelStore(), new com.safepath.backend.websocket.InMemoryChannelRegistry(), permissions, mock(com.safepath.backend.notification.NotificationService.class), org.mockito.Mockito.mock(com.safepath.backend.risk.service.RiskSessionBridge.class));
    private final ActiveSession session = new ActiveSession(UUID.randomUUID(), 42L, Instant.now());

    GuardianSelectionServiceTest() {
        sessions.saveSession(session);
    }

    @Test
    void validatesAcceptedLocationGuardiansAndNormalizesDuplicates() {

        when(permissions.hasGuardianPermission(42L, 43L, com.safepath.backend.guardian.entity.PermissionType.LOCATION)).thenReturn(true);
        assertEquals(Set.of(43L), lifecycle.validateForStart(42L, List.of(43L, 43L)));
        verify(permissions, times(1)).hasGuardianPermission(42L, 43L, com.safepath.backend.guardian.entity.PermissionType.LOCATION);
        assertTrue(selected.findBySessionId(session.id()).isEmpty());
    }

    @Test
    void activeSessionChangesAreDeniedIncludingRepeatedChanges() {
        selected.save(session.id(), Set.of(43L));
        assertThrows(AccessDeniedException.class, () -> selection.selectGuardian(session.id(), 42L, 43L));
        assertThrows(AccessDeniedException.class, () -> selection.deselectGuardian(session.id(), 42L, 43L));
        assertEquals(Set.of(43L), selection.getSelectedGuardians(session.id(), 42L));
        verifyNoInteractions(permissions);
    }

    @Test
    void nonOwnerCannotReadOrChangeSelection() {
        selected.save(session.id(), Set.of(43L));
        assertThrows(SecurityException.class, () -> selection.selectGuardian(session.id(), 99L, 44L));
        assertThrows(SecurityException.class, () -> selection.deselectGuardian(session.id(), 99L, 43L));
        assertThrows(SecurityException.class, () -> selection.getSelectedGuardians(session.id(), 99L));
        assertEquals(Set.of(43L), selected.findBySessionId(session.id()));
    }

    @Test
    void selfAndUnacceptedGuardianAreRejected() {
        assertThrows(AccessDeniedException.class, () -> lifecycle.validateForStart(42L, List.of(42L)));
        assertThrows(AccessDeniedException.class, () -> lifecycle.validateForStart(42L, List.of(43L)));
    }

    @Test
    void lookupFailureIsPropagatedWithoutMutation() {
        when(permissions.hasGuardianPermission(42L, 43L, com.safepath.backend.guardian.entity.PermissionType.LOCATION)).thenThrow(new IllegalStateException("Unavailable"));
        assertThrows(IllegalStateException.class, () -> lifecycle.validateForStart(42L, List.of(43L)));
        assertTrue(selected.findBySessionId(session.id()).isEmpty());
    }

    @Test
    void missingAndEndedSessionsRejectReadAndChanges() {
        sessions.remove(session.id());
        assertThrows(NoSuchElementException.class, () -> selection.selectGuardian(session.id(), 42L, 43L));
        assertThrows(NoSuchElementException.class, () -> selection.deselectGuardian(session.id(), 42L, 43L));
        assertThrows(NoSuchElementException.class, () -> selection.getSelectedGuardians(session.id(), 42L));
    }

    @Test
    void rejectsInvalidGuardianAndOwnerArguments() {
        assertThrows(IllegalArgumentException.class, () -> lifecycle.validateForStart(42L, null));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.validateForStart(42L, List.of()));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.validateForStart(null, List.of(43L)));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.validateForStart(42L, java.util.Arrays.asList(43L, null)));
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertThrows(IllegalArgumentException.class, () -> selection.selectGuardian(session.id(), 42L, id));
            assertThrows(IllegalArgumentException.class, () -> selection.deselectGuardian(session.id(), 42L, id));
            assertThrows(IllegalArgumentException.class, () -> selection.selectGuardian(session.id(), id, 43L));
        }
        verifyNoInteractions(permissions);
    }

    @Test
    void ownerSelectionSnapshotIsImmutableAndDoesNotGrantLocationPermission() {
        selected.save(session.id(), Set.of(43L));
        assertThrows(UnsupportedOperationException.class, () -> selection.getSelectedGuardians(session.id(), 42L).add(44L));


        assertThrows(AccessDeniedException.class, () -> lifecycle.validateForStart(42L, List.of(43L)));
    }
}