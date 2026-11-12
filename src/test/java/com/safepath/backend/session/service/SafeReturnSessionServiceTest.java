package com.safepath.backend.session.service;

import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.store.ActiveSessionStore;
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

	@BeforeEach
	void setUp() {
		store = new ActiveSessionStore();
		service = new SafeReturnSessionService(store);
	}

	@Test
	void startsAndStoresSessionWithUuidAndStartTime() {
		Instant beforeStart = Instant.now();

		ActiveSession session = service.startSession(42L);

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
		ActiveSession existing = service.startSession(42L);

		assertThrows(IllegalStateException.class, () -> service.startSession(42L));

		assertEquals(existing, store.findByUserId(42L).orElseThrow());
		assertEquals(existing, store.findById(existing.id()).orElseThrow());
	}

	@Test
	void rejectsNullZeroAndNegativeUserIds() {
		assertThrows(IllegalArgumentException.class, () -> service.startSession(null));
		assertThrows(IllegalArgumentException.class, () -> service.startSession(0L));
		assertThrows(IllegalArgumentException.class, () -> service.startSession(-1L));

		assertTrue(store.findByUserId(0L).isEmpty());
		assertTrue(store.findByUserId(-1L).isEmpty());
		assertTrue(store.findByUserId(1L).isEmpty());
	}

	@Test
	void retrievesOwnActiveSessionStartedByService() {
		ActiveSession startedSession = service.startSession(42L);

		assertEquals(startedSession, service.getSessionStatus(startedSession.id(), 42L));
	}

	@Test
	void rejectsMissingSessionId() {
		assertThrows(NoSuchElementException.class,
				() -> service.getSessionStatus(UUID.randomUUID(), 42L));
	}

	@Test
	void rejectsSessionOwnedByAnotherUser() {
		ActiveSession session = service.startSession(42L);

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
		ActiveSession session = service.startSession(42L);
		store.remove(session.id());

		assertThrows(NoSuchElementException.class,
				() -> service.getSessionStatus(session.id(), 42L));
	}

	@Test
	void endsOwnSessionAndRemovesItFromBothIndexes() {
		ActiveSession session = service.startSession(42L);

		service.endSession(session.id(), 42L);

		assertTrue(store.findById(session.id()).isEmpty());
		assertTrue(store.findByUserId(42L).isEmpty());
		assertThrows(NoSuchElementException.class,
				() -> service.getSessionStatus(session.id(), 42L));
	}

	@Test
	void allowsUserToStartAnotherSessionAfterEndingCurrentOne() {
		ActiveSession endedSession = service.startSession(42L);
		service.endSession(endedSession.id(), 42L);

		ActiveSession newSession = service.startSession(42L);

		assertNotEquals(endedSession.id(), newSession.id());
		assertEquals(newSession, store.findByUserId(42L).orElseThrow());
		assertTrue(store.findById(endedSession.id()).isEmpty());
	}

	@Test
	void rejectsEndingMissingOrAlreadyEndedSession() {
		UUID missingSessionId = UUID.randomUUID();
		assertThrows(NoSuchElementException.class,
				() -> service.endSession(missingSessionId, 42L));

		ActiveSession session = service.startSession(42L);
		service.endSession(session.id(), 42L);
		assertThrows(NoSuchElementException.class,
				() -> service.endSession(session.id(), 42L));
	}

	@Test
	void rejectsEndingAnotherUsersSessionWithoutChangingIt() {
		ActiveSession session = service.startSession(42L);

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
		ActiveSession session = service.startSession(42L);
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
						service.startSession(42L);
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
}
