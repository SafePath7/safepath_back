package com.safepath.backend.session.service;

import com.safepath.backend.guardian.service.GuardianRegistrationService;
import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import com.safepath.backend.session.store.SessionChannelStore;
import com.safepath.backend.websocket.InMemoryChannelRegistry;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeReturnSessionServiceTest {

	private ActiveSessionStore store;
	private SafeReturnSessionService service;
	private SelectedGuardianStore selectedGuardianStore;
	private GuardianRegistrationService permissions;

	@BeforeEach
	void setUp() {
		store = new ActiveSessionStore();
		selectedGuardianStore = new SelectedGuardianStore(store);
		permissions = org.mockito.Mockito.mock(GuardianRegistrationService.class);
		service = new SafeReturnSessionService(store, selectedGuardianStore, new SessionChannelStore(), new InMemoryChannelRegistry(), permissions, org.mockito.Mockito.mock(com.safepath.backend.notification.NotificationService.class));
	}

	@Test
	void successfulEndClearsOnlyItsSelectionAndRejectsLaterSaves() {
		ActiveSession session = startSession(42L);
		ActiveSession other = startSession(43L);
		selectedGuardianStore.save(session.id(), Set.of(44L));
		selectedGuardianStore.save(other.id(), Set.of(45L));
		service.endSession(session.id(), 42L);
		assertTrue(selectedGuardianStore.findBySessionId(session.id()).isEmpty());
		assertEquals(Set.of(45L), selectedGuardianStore.findBySessionId(other.id()));
		assertEquals(other, store.findById(other.id()).orElseThrow());
		assertThrows(NoSuchElementException.class,
				() -> selectedGuardianStore.save(session.id(), Set.of(44L)));
	}

	@Test
	void failedEndDoesNotRemoveSelection() {
		ActiveSession session = startSession(42L);
		selectedGuardianStore.save(session.id(), Set.of(44L));
		assertThrows(SecurityException.class, () -> service.endSession(session.id(), 43L));
		assertEquals(Set.of(44L), selectedGuardianStore.findBySessionId(session.id()));
		assertEquals(session, store.findById(session.id()).orElseThrow());
		store.remove(session.id());
		assertThrows(NoSuchElementException.class, () -> service.endSession(session.id(), 42L));
		assertEquals(Set.of(44L), selectedGuardianStore.findBySessionId(session.id()));
	}

	@Test
	void concurrentSelectionSaveAndEndCannotLeaveSelectionBehind() throws Exception {
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			for (int i = 0; i < 30; i++) {
				ActiveSession session = startSession(42L);
				selectedGuardianStore.save(session.id(), Set.of(44L));
				CountDownLatch startGate = new CountDownLatch(1);
				Future<?> save = executor.submit(() -> {
					startGate.await();
					try {
						selectedGuardianStore.save(session.id(), Set.of(45L));
					} catch (NoSuchElementException expected) {
						// Ending first makes a late selection invalid.
					}
					return null;
				});
				Future<?> end = executor.submit(() -> {
					startGate.await();
					service.endSession(session.id(), 42L);
					return null;
				});
				startGate.countDown();
				save.get(5, java.util.concurrent.TimeUnit.SECONDS);
				end.get(5, java.util.concurrent.TimeUnit.SECONDS);
				assertTrue(store.findById(session.id()).isEmpty());
				assertTrue(selectedGuardianStore.findBySessionId(session.id()).isEmpty());
			}
		}
	}

	@Test
	void startsAndStoresSessionWithUuidAndStartTime() {
		Instant beforeStart = Instant.now();

		ActiveSession session = startSession(42L);

		Instant afterStart = Instant.now();
		assertNotNull(session.id());
		assertEquals(42L, session.userId());
		assertFalse(session.startedAt().isBefore(beforeStart));
		assertFalse(session.startedAt().isAfter(afterStart));
		assertEquals(session, store.findById(session.id()).orElseThrow());
		assertEquals(session, store.findByUserId(42L).orElseThrow());
	}

	@Test
	void rejectsDuplicateSessionStartAndPreservesExistingSession() {
		ActiveSession existing = startSession(42L);

		assertThrows(IllegalStateException.class, () -> startSession(42L));

		assertEquals(existing, store.findByUserId(42L).orElseThrow());
		assertEquals(existing, store.findById(existing.id()).orElseThrow());
	}

	@Test
	void rejectsNullZeroAndNegativeUserIds() {
		assertThrows(IllegalArgumentException.class, () -> startSession(null));
		assertThrows(IllegalArgumentException.class, () -> startSession(0L));
		assertThrows(IllegalArgumentException.class, () -> startSession(-1L));

		assertTrue(store.findByUserId(0L).isEmpty());
		assertTrue(store.findByUserId(-1L).isEmpty());
		assertTrue(store.findByUserId(1L).isEmpty());
	}

	@Test
	void retrievesOwnActiveSessionStartedByService() {
		ActiveSession startedSession = startSession(42L);

		assertEquals(startedSession, service.getSessionStatus(startedSession.id(), 42L));
	}

	@Test
	void rejectsMissingSessionId() {
		assertThrows(NoSuchElementException.class,
				() -> service.getSessionStatus(UUID.randomUUID(), 42L));
	}

	@Test
	void rejectsSessionOwnedByAnotherUser() {
		ActiveSession session = startSession(42L);

		assertThrows(SecurityException.class,
				() -> service.getSessionStatus(session.id(), 43L));
		assertEquals(session, store.findById(session.id()).orElseThrow());
	}

	@Test
	void rejectsInvalidStatusLookupArguments() {
		UUID sessionId = UUID.randomUUID();

		assertThrows(IllegalArgumentException.class, () -> service.getSessionStatus(null, 42L));
		assertThrows(IllegalArgumentException.class, () -> service.getSessionStatus(sessionId, null));
		assertThrows(IllegalArgumentException.class, () -> service.getSessionStatus(sessionId, 0L));
		assertThrows(IllegalArgumentException.class, () -> service.getSessionStatus(sessionId, -1L));
	}

	@Test
	void removedSessionCannotBeRetrieved() {
		ActiveSession session = startSession(42L);
		store.remove(session.id());

		assertThrows(NoSuchElementException.class,
				() -> service.getSessionStatus(session.id(), 42L));
	}

	@Test
	void endsOwnSessionAndRemovesItFromBothIndexes() {
		ActiveSession session = startSession(42L);

		service.endSession(session.id(), 42L);

		assertTrue(store.findById(session.id()).isEmpty());
		assertTrue(store.findByUserId(42L).isEmpty());
		assertThrows(NoSuchElementException.class,
				() -> service.getSessionStatus(session.id(), 42L));
	}

	@Test
	void allowsUserToStartAnotherSessionAfterEndingCurrentOne() {
		ActiveSession endedSession = startSession(42L);
		service.endSession(endedSession.id(), 42L);

		ActiveSession newSession = startSession(42L);

		assertNotEquals(endedSession.id(), newSession.id());
		assertEquals(newSession, store.findByUserId(42L).orElseThrow());
		assertTrue(store.findById(endedSession.id()).isEmpty());
	}

	@Test
	void rejectsEndingMissingOrAlreadyEndedSession() {
		UUID missingSessionId = UUID.randomUUID();
		assertThrows(NoSuchElementException.class,
				() -> service.endSession(missingSessionId, 42L));

		ActiveSession session = startSession(42L);
		service.endSession(session.id(), 42L);
		assertThrows(NoSuchElementException.class,
				() -> service.endSession(session.id(), 42L));
	}

	@Test
	void rejectsEndingAnotherUsersSessionWithoutChangingIt() {
		ActiveSession session = startSession(42L);

		assertThrows(SecurityException.class,
				() -> service.endSession(session.id(), 43L));

		assertEquals(session, store.findById(session.id()).orElseThrow());
		assertEquals(session, store.findByUserId(42L).orElseThrow());
		assertTrue(store.findByUserId(43L).isEmpty());
	}

	@Test
	void rejectsInvalidEndSessionArguments() {
		UUID sessionId = UUID.randomUUID();

		assertThrows(IllegalArgumentException.class, () -> service.endSession(null, 42L));
		assertThrows(IllegalArgumentException.class, () -> service.endSession(sessionId, null));
		assertThrows(IllegalArgumentException.class, () -> service.endSession(sessionId, 0L));
		assertThrows(IllegalArgumentException.class, () -> service.endSession(sessionId, -1L));
	}

	@Test
	void concurrentEndRequestsRemoveSessionOnceAndKeepIndexesConsistent() throws Exception {
		ActiveSession session = startSession(42L);
		int requestCount = 20;
		CountDownLatch startGate = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(8);
		List<Future<Boolean>> results = new ArrayList<>();

		try {
			for (int i = 0; i < requestCount; i++) {
				results.add(executor.submit(() -> {
					startGate.await();
					try {
						service.endSession(session.id(), 42L);
						return true;
					}
					catch (NoSuchElementException exception) {
						return false;
					}
				}));
			}

			startGate.countDown();
			int successfulEnds = 0;
			for (Future<Boolean> result : results) {
				if (result.get()) {
					successfulEnds++;
				}
			}

			assertEquals(1, successfulEnds);
			assertTrue(store.findById(session.id()).isEmpty());
			assertTrue(store.findByUserId(42L).isEmpty());
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void concurrentStartsForSameUserCreateOnlyOneSession() throws Exception {
		int requestCount = 20;
		CountDownLatch startGate = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(8);
		List<Future<Boolean>> results = new ArrayList<>();

		try {
			for (int i = 0; i < requestCount; i++) {
				results.add(executor.submit(() -> {
					startGate.await();
					try {
						startSession(42L);
						return true;
					}
					catch (IllegalStateException exception) {
						return false;
					}
				}));
			}

			startGate.countDown();
			int startedCount = 0;
			for (Future<Boolean> result : results) {
				if (result.get()) {
					startedCount++;
				}
			}

			assertEquals(1, startedCount);
			ActiveSession savedSession = store.findByUserId(42L).orElseThrow();
			assertEquals(savedSession, store.findById(savedSession.id()).orElseThrow());
		}
		finally {
			executor.shutdownNow();
		}
	}

	private ActiveSession startSession(Long userId) {
		if (userId != null && userId > 0) {
			Long guardianId = userId + 1000;

			org.mockito.Mockito.when(permissions.hasGuardianPermission(userId, guardianId, com.safepath.backend.guardian.entity.PermissionType.LOCATION)).thenReturn(true);
			return service.startSession(userId, List.of(guardianId));
		}
		return service.startSession(userId, List.of(1001L));
	}
}
