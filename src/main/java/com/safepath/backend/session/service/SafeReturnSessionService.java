package com.safepath.backend.session.service;

import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.store.ActiveSessionStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class SafeReturnSessionService {

	private final ActiveSessionStore activeSessionStore;

	public SafeReturnSessionService(ActiveSessionStore activeSessionStore) {
		this.activeSessionStore = activeSessionStore;
	}

	public ActiveSession startSession(Long userId) {
		if (userId == null || userId <= 0) {
			throw new IllegalArgumentException("userId must be a positive number");
		}

		ActiveSession session = new ActiveSession(UUID.randomUUID(), userId, Instant.now());
		activeSessionStore.saveSession(session);
		return session;
	}

	public ActiveSession getSessionStatus(UUID sessionId, Long userId) {
		if (sessionId == null) {
			throw new IllegalArgumentException("sessionId must not be null");
		}
		if (userId == null || userId <= 0) {
			throw new IllegalArgumentException("userId must be a positive number");
		}

		ActiveSession session = activeSessionStore.findById(sessionId)
				.orElseThrow(() -> new NoSuchElementException("Active session not found"));
		if (!Objects.equals(session.userId(), userId)) {
			throw new SecurityException("User is not the owner of this active session");
		}

		return session;
	}
}
