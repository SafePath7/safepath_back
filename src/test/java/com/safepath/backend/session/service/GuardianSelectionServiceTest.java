package com.safepath.backend.session.service;

import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.LatestLocationStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import com.safepath.backend.session.store.SessionChannelStore;
import com.safepath.backend.websocket.InMemoryChannelRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GuardianSelectionServiceTest {
	private final ActiveSessionStore sessions = new ActiveSessionStore();
	private final SelectedGuardianStore selected = new SelectedGuardianStore(sessions);
	private final SafeReturnSessionService lifecycle = new SafeReturnSessionService(sessions, selected,
			new SessionChannelStore(), new InMemoryChannelRegistry(), new LatestLocationStore(sessions));
	private final GuardianPermissionService permissions = mock(GuardianPermissionService.class);
	private final GuardianSelectionService selection = new GuardianSelectionService(sessions, lifecycle, selected, permissions);
	private final ActiveSession session = lifecycle.startSession(42L);

	@Test
	void ownerCanSelectMultipleAcceptedGuardiansAndDuplicatesAreIdempotent() {
		when(permissions.hasAcceptedRelationship(42L, 43L)).thenReturn(true);
		when(permissions.hasAcceptedRelationship(42L, 44L)).thenReturn(true);
		selection.selectGuardian(session.id(), 42L, 43L);
		selection.selectGuardian(session.id(), 42L, 43L);
		selection.selectGuardian(session.id(), 42L, 44L);
		assertEquals(Set.of(43L, 44L), selected.findBySessionId(session.id()));
	}

	@Test
	void deselectionIsIdempotentAndDoesNotRequireStillAcceptedRelationship() {
		selected.save(session.id(), Set.of(43L, 44L));
		selection.deselectGuardian(session.id(), 42L, 43L);
		selection.deselectGuardian(session.id(), 42L, 43L);
		assertEquals(Set.of(44L), selected.findBySessionId(session.id()));
		selection.deselectGuardian(session.id(), 42L, 44L);
		assertTrue(selected.findBySessionId(session.id()).isEmpty());
		verifyNoInteractions(permissions);
	}

	@Test
	void nonOwnerCannotSelectOrDeselect() {
		selected.save(session.id(), Set.of(43L));
		assertThrows(SecurityException.class, () -> selection.selectGuardian(session.id(), 99L, 44L));
		assertThrows(SecurityException.class, () -> selection.deselectGuardian(session.id(), 99L, 43L));
		assertEquals(Set.of(43L), selected.findBySessionId(session.id()));
		verifyNoInteractions(permissions);
	}

	@Test
	void selfAndUnacceptedGuardianAreRejectedWithoutChangingSelection() {
		selected.save(session.id(), Set.of(44L));
		assertThrows(AccessDeniedException.class, () -> selection.selectGuardian(session.id(), 42L, 42L));
		assertThrows(AccessDeniedException.class, () -> selection.selectGuardian(session.id(), 42L, 43L));
		assertEquals(Set.of(44L), selected.findBySessionId(session.id()));
	}

	@Test
	void lookupFailureDoesNotMutateSelection() {
		selected.save(session.id(), Set.of(44L));
		when(permissions.hasAcceptedRelationship(42L, 43L)).thenThrow(new IllegalStateException("Unavailable"));
		assertThrows(IllegalStateException.class, () -> selection.selectGuardian(session.id(), 42L, 43L));
		assertEquals(Set.of(44L), selected.findBySessionId(session.id()));
	}

	@Test
	void missingAndEndedSessionsRejectSelectionAndDeselection() {
		assertThrows(NoSuchElementException.class, () -> selection.selectGuardian(UUID.randomUUID(), 42L, 43L));
		lifecycle.endSession(session.id(), 42L);
		assertThrows(NoSuchElementException.class, () -> selection.selectGuardian(session.id(), 42L, 43L));
		assertThrows(NoSuchElementException.class, () -> selection.deselectGuardian(session.id(), 42L, 43L));
		verifyNoInteractions(permissions);
	}

	@Test
	void rejectsInvalidGuardianAndOwnerArguments() {
		for (Long id : new Long[]{null, 0L, -1L}) {
			assertThrows(IllegalArgumentException.class, () -> selection.selectGuardian(session.id(), 42L, id));
			assertThrows(IllegalArgumentException.class, () -> selection.deselectGuardian(session.id(), 42L, id));
			assertThrows(IllegalArgumentException.class, () -> selection.selectGuardian(session.id(), id, 43L));
		}
		assertThrows(IllegalArgumentException.class, () -> selection.selectGuardian(null, 42L, 43L));
		verifyNoInteractions(permissions);
	}

	@Test
	void selectionAndEndCannotLeaveSelectionBehind() throws Exception {
		when(permissions.hasAcceptedRelationship(42L, 43L)).thenReturn(true);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var select = executor.submit(() -> {
				start.await();
				try {
					selection.selectGuardian(session.id(), 42L, 43L);
				} catch (NoSuchElementException ended) {
					return false;
				}
				return true;
			});
			var end = executor.submit(() -> {
				start.await();
				lifecycle.endSession(session.id(), 42L);
				return true;
			});
			start.countDown();
			select.get(5, TimeUnit.SECONDS);
			end.get(5, TimeUnit.SECONDS);
		}
		assertTrue(selected.findBySessionId(session.id()).isEmpty());
	}
}
