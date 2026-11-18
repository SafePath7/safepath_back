package com.safepath.backend.session.store;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectedGuardianStoreTest {

	private final ActiveSessionStore activeSessionStore = new ActiveSessionStore();
	private final SelectedGuardianStore store = new SelectedGuardianStore(activeSessionStore);
	private long activeUserId = 100;
	private final UUID sessionId = createActiveSession();

	private UUID createActiveSession() {
		UUID id = UUID.randomUUID();
		activeSessionStore.saveSession(new com.safepath.backend.session.model.ActiveSession(
				id, activeUserId++, java.time.Instant.now()));
		return id;
	}
	@Test
	void rejectsSelectionForMissingAndRemovedSession() {
		assertThrows(java.util.NoSuchElementException.class,
				() -> store.save(UUID.randomUUID(), Set.of(42L)));
		activeSessionStore.remove(sessionId);
		assertThrows(java.util.NoSuchElementException.class,
				() -> store.save(sessionId, Set.of(42L)));
		assertTrue(store.findBySessionId(sessionId).isEmpty());
	}

	@Test
	void savesAndFindsGuardiansIndependentlyForEachSession() {
		UUID otherSessionId = createActiveSession();
		store.save(sessionId, Set.of(42L, 43L));
		store.save(otherSessionId, Set.of(44L));

		assertEquals(Set.of(42L, 43L), store.findBySessionId(sessionId));
		assertTrue(store.isSelectedGuardian(sessionId, 42L));
		assertFalse(store.isSelectedGuardian(sessionId, 44L));
		assertEquals(Set.of(44L), store.findBySessionId(otherSessionId));
	}

	@Test
	void missingOrEmptySelectionDoesNotGrantAccess() {
		assertTrue(store.findBySessionId(sessionId).isEmpty());
		assertFalse(store.isSelectedGuardian(sessionId, 42L));
		store.save(sessionId, Set.of());
		assertTrue(store.findBySessionId(sessionId).isEmpty());
		assertFalse(store.isSelectedGuardian(sessionId, 42L));
	}

	@Test
	void replacesSelectionWithoutMutatingPreviousSnapshot() {
		store.save(sessionId, Set.of(42L));
		Set<Long> previous = store.findBySessionId(sessionId);
		store.save(sessionId, Set.of(43L));

		assertEquals(Set.of(42L), previous);
		assertEquals(Set.of(43L), store.findBySessionId(sessionId));
		assertFalse(store.isSelectedGuardian(sessionId, 42L));
	}

	@Test
	void copiesInputAndReturnsImmutableSnapshots() {
		Set<Long> input = new HashSet<>(Set.of(42L));
		store.save(sessionId, input);
		input.clear();
		input.add(43L);

		Set<Long> result = store.findBySessionId(sessionId);
		assertEquals(Set.of(42L), result);
		assertThrows(UnsupportedOperationException.class, () -> result.add(44L));
		assertEquals(Set.of(42L), store.findBySessionId(sessionId));
	}

	@Test
	void removesOnlyRequestedSelectionAndAllowsRepeatedRemoval() {
		UUID otherSessionId = createActiveSession();
		store.save(sessionId, Set.of(42L));
		store.save(otherSessionId, Set.of(43L));
		store.remove(sessionId);
		store.remove(sessionId);

		assertTrue(store.findBySessionId(sessionId).isEmpty());
		assertFalse(store.isSelectedGuardian(sessionId, 42L));
		assertEquals(Set.of(43L), store.findBySessionId(otherSessionId));
	}

	@Test
	void rejectsNullArguments() {
		assertThrows(NullPointerException.class, () -> store.save(null, Set.of(42L)));
		assertThrows(NullPointerException.class, () -> store.save(sessionId, null));
		assertThrows(NullPointerException.class, () -> store.findBySessionId(null));
		assertThrows(NullPointerException.class, () -> store.isSelectedGuardian(null, 42L));
		assertThrows(NullPointerException.class, () -> store.remove(null));
		Set<Long> withNull = new HashSet<>();
		withNull.add(null);
		assertThrows(NullPointerException.class, () -> store.save(sessionId, withNull));
		assertThrows(IllegalArgumentException.class, () -> store.isSelectedGuardian(sessionId, null));
	}

	@Test
	void invalidReplacementPreservesExistingSelection() {
		store.save(sessionId, Set.of(42L));
		for (long invalidId : new long[]{0L, -1L}) {
			assertThrows(IllegalArgumentException.class,
					() -> store.save(sessionId, Set.of(43L, invalidId)));
			assertThrows(IllegalArgumentException.class,
					() -> store.isSelectedGuardian(sessionId, invalidId));
		}
		assertEquals(Set.of(42L), store.findBySessionId(sessionId));
	}
}
