package com.safepath.backend.session.store;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class SelectedGuardianStore {
	private final ActiveSessionStore activeSessionStore;

	public SelectedGuardianStore(ActiveSessionStore activeSessionStore) {
		this.activeSessionStore = Objects.requireNonNull(activeSessionStore);
	}

	private final ConcurrentMap<UUID, Set<Long>> selectedGuardianIdsBySessionId = new ConcurrentHashMap<>();

	public void save(UUID sessionId, Set<Long> guardianIds) {
		Objects.requireNonNull(sessionId, "sessionId must not be null");
		Set<Long> snapshot = Set.copyOf(guardianIds);
		snapshot.forEach(this::validateGuardianId);
		// Share the lifecycle lock with endSession so a late save cannot restore an ended session's selection.
		synchronized (activeSessionStore) {
			if (activeSessionStore.findById(sessionId).isEmpty()) {
				throw new NoSuchElementException("Active session not found");
			}
			selectedGuardianIdsBySessionId.put(sessionId, snapshot);
		}
	}

	public Set<Long> findBySessionId(UUID sessionId) {
		Objects.requireNonNull(sessionId, "sessionId must not be null");
		return selectedGuardianIdsBySessionId.getOrDefault(sessionId, Set.of());
	}

	public boolean isSelectedGuardian(UUID sessionId, Long guardianId) {
		validateGuardianId(guardianId);
		return findBySessionId(sessionId).contains(guardianId);
	}

	public void remove(UUID sessionId) {
		Objects.requireNonNull(sessionId, "sessionId must not be null");
		selectedGuardianIdsBySessionId.remove(sessionId);
	}

	private void validateGuardianId(Long guardianId) {
		if (guardianId == null || guardianId <= 0) {
			throw new IllegalArgumentException("guardianId must be a positive number");
		}
	}
}
