package com.safepath.backend.session.store;

import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.websocket.dto.LocationUpdateRequest;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class LatestLocationStore {
	private final ActiveSessionStore activeSessionStore;
	private final ConcurrentMap<UUID, LocationUpdateRequest> locationsBySessionId = new ConcurrentHashMap<>();

	public LatestLocationStore(ActiveSessionStore activeSessionStore) {
		this.activeSessionStore = Objects.requireNonNull(activeSessionStore);
	}

	/** userId must come from the authenticated publisher, never a client-supplied identity. */
	public LocationUpdateRequest updateLocation(UUID sessionId, Long userId, LocationUpdateRequest location) {
		if (sessionId == null || userId == null || userId <= 0 || location == null) {
			throw new IllegalArgumentException("Session, authenticated user and location are required");
		}
		if (!Double.isFinite(location.latitude()) || location.latitude() < -90 || location.latitude() > 90
				|| !Double.isFinite(location.longitude()) || location.longitude() < -180 || location.longitude() > 180
				|| location.capturedAt() == null
				|| (location.accuracyMeters() != null
						&& (!Double.isFinite(location.accuracyMeters()) || location.accuracyMeters() < 0))) {
			throw new IllegalArgumentException("Invalid location measurement");
		}
		synchronized (activeSessionStore) {
			ActiveSession session = requireActiveSession(sessionId);
			if (!session.userId().equals(userId)) {
				throw new SecurityException("User is not the owner of this active session");
			}
			return locationsBySessionId.compute(sessionId, (id, previous) ->
					previous == null || location.capturedAt().isAfter(previous.capturedAt()) ? location : previous);
		}
	}

	/** Internal lookup; guardian callers must first pass monitoring authorization. */
	public Optional<LocationUpdateRequest> findBySessionId(UUID sessionId) {
		synchronized (activeSessionStore) {
			requireActiveSession(Objects.requireNonNull(sessionId));
			return Optional.ofNullable(locationsBySessionId.get(sessionId));
		}
	}

	public void remove(UUID sessionId) {
		locationsBySessionId.remove(Objects.requireNonNull(sessionId));
	}

	private ActiveSession requireActiveSession(UUID sessionId) {
		return activeSessionStore.findById(sessionId)
				.orElseThrow(() -> new NoSuchElementException("Active session not found"));
	}
}
