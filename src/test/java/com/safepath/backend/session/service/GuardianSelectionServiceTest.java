package com.safepath.backend.session.service;

import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.model.MonitoringPermissionType;
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
    private final GuardianPermissionService permissions = mock(GuardianPermissionService.class);
    private final GuardianSelectionService selection = new GuardianSelectionService(sessions, selected, permissions);
    private final ActiveSession session = new ActiveSession(UUID.randomUUID(), 42L, Instant.now());

    GuardianSelectionServiceTest() {
        sessions.saveSession(session);
    }

    @Test
    void validatesAcceptedLocationGuardiansAndNormalizesDuplicates() {
        when(permissions.hasAcceptedRelationship(42L, 43L)).thenReturn(true);
        when(permissions.hasPermission(42L, 43L, MonitoringPermissionType.LOCATION)).thenReturn(true);
        assertEquals(Set.of(43L), selection.validateForStart(42L, List.of(43L, 43L)));
        verify(permissions, times(1)).hasAcceptedRelationship(42L, 43L);
        verify(permissions, times(1)).hasPermission(42L, 43L, MonitoringPermissionType.LOCATION);
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
        assertThrows(AccessDeniedException.class, () -> selection.validateForStart(42L, List.of(42L)));
        assertThrows(AccessDeniedException.class, () -> selection.validateForStart(42L, List.of(43L)));
    }

    @Test
    void lookupFailureIsPropagatedWithoutMutation() {
        when(permissions.hasAcceptedRelationship(42L, 43L)).thenThrow(new IllegalStateException("Unavailable"));
        assertThrows(IllegalStateException.class, () -> selection.validateForStart(42L, List.of(43L)));
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
        assertThrows(IllegalArgumentException.class, () -> selection.validateForStart(42L, null));
        assertThrows(IllegalArgumentException.class, () -> selection.validateForStart(42L, List.of()));
        assertThrows(IllegalArgumentException.class, () -> selection.validateForStart(null, List.of(43L)));
        assertThrows(IllegalArgumentException.class, () -> selection.validateForStart(42L, java.util.Arrays.asList(43L, null)));
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
        when(permissions.hasAcceptedRelationship(42L, 43L)).thenReturn(true);
        when(permissions.hasPermission(42L, 43L, MonitoringPermissionType.STATUS)).thenReturn(true);
        assertThrows(AccessDeniedException.class, () -> selection.validateForStart(42L, List.of(43L)));
    }
}