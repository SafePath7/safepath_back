package com.safepath.backend.session.store;

import com.safepath.backend.session.model.ActiveSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveSessionStoreTest {

	private ActiveSessionStore store;

	@BeforeEach
	void setUp() {
		store = new ActiveSessionStore();
	}

	@Test
	void savesAndFindsSessionByIdAndUserId() {
		ActiveSession session = new ActiveSession(UUID.randomUUID(), 42L);

		store.saveSession(session);

		assertEquals(session, store.findById(session.id()).orElseThrow());
		assertEquals(session, store.findByUserId(session.userId()).orElseThrow());
	}

	@Test
	void returnsEmptyWhenSessionDoesNotExist() {
		assertTrue(store.findById(UUID.randomUUID()).isEmpty());
		assertTrue(store.findByUserId(42L).isEmpty());
	}

	@Test
	void removesSessionAndAllowsRemovingItAgain() {
		ActiveSession session = new ActiveSession(UUID.randomUUID(), 42L);
		store.saveSession(session);

		store.remove(session.id());
		store.remove(session.id());

		assertTrue(store.findById(session.id()).isEmpty());
		assertTrue(store.findByUserId(session.userId()).isEmpty());
	}

	@Test
	void rejectsDuplicateSessionIdWithoutReplacingExistingSession() {
		UUID sessionId = UUID.randomUUID();
		ActiveSession existing = new ActiveSession(sessionId, 42L);
		store.saveSession(existing);

		assertThrows(IllegalStateException.class,
				() -> store.saveSession(new ActiveSession(sessionId, 43L)));

		assertEquals(existing, store.findById(sessionId).orElseThrow());
		assertEquals(existing, store.findByUserId(42L).orElseThrow());
		assertTrue(store.findByUserId(43L).isEmpty());
	}

	@Test
	void rejectsSecondActiveSessionForUserWithoutReplacingExistingSession() {
		ActiveSession existing = new ActiveSession(UUID.randomUUID(), 42L);
		store.saveSession(existing);

		assertThrows(IllegalStateException.class,
				() -> store.saveSession(new ActiveSession(UUID.randomUUID(), 42L)));

		assertEquals(existing, store.findByUserId(42L).orElseThrow());
	}

	@Test
	void concurrentSavesForSameUserAllowOnlyOneSession() throws Exception {
		int requestCount = 20;
		CountDownLatch startGate = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(8);
		List<Future<Boolean>> results = new ArrayList<>();

		try {
			for (int i = 0; i < requestCount; i++) {
				results.add(executor.submit(() -> {
					startGate.await();
					try {
						store.saveSession(new ActiveSession(UUID.randomUUID(), 42L));
						return true;
					}
					catch (IllegalStateException exception) {
						return false;
					}
				}));
			}

			startGate.countDown();
			int savedCount = 0;
			for (Future<Boolean> result : results) {
				if (result.get()) {
					savedCount++;
				}
			}

			assertEquals(1, savedCount);
			ActiveSession savedSession = store.findByUserId(42L).orElseThrow();
			assertEquals(savedSession, store.findById(savedSession.id()).orElseThrow());
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void rejectsNullIdentifiersInSessionModel() {
		assertThrows(NullPointerException.class, () -> new ActiveSession(null, 42L));
		assertThrows(NullPointerException.class, () -> new ActiveSession(UUID.randomUUID(), null));
	}

	@Test
	void rejectsNullArgumentsToStoreOperations() {
		assertThrows(NullPointerException.class, () -> store.saveSession(null));
		assertThrows(NullPointerException.class, () -> store.findById(null));
		assertThrows(NullPointerException.class, () -> store.findByUserId(null));
		assertThrows(NullPointerException.class, () -> store.remove(null));
	}
}
