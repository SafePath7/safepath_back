package com.safepath.backend.session.store;

import com.safepath.backend.session.model.ActiveSession;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ActiveSessionStore {

	public enum RemovalResult {
		REMOVED,
		NOT_FOUND,
		NOT_OWNER
	}

	private final ConcurrentHashMap<UUID, ActiveSession> sessionsById = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, UUID> sessionIdsByUserId = new ConcurrentHashMap<>();

	public synchronized void saveSession(ActiveSession session) {
		if (sessionsById.containsKey(session.id())) {
			throw new IllegalStateException("An active session with this ID already exists");
		}
		if (sessionIdsByUserId.containsKey(session.userId())) {
			throw new IllegalStateException("The user already has an active session");
		}

		sessionsById.put(session.id(), session);
		sessionIdsByUserId.put(session.userId(), session.id());
	}

	public synchronized Optional<ActiveSession> findById(UUID sessionId) {
		return Optional.ofNullable(sessionsById.get(sessionId));
	}

	public synchronized Optional<ActiveSession> findByUserId(Long userId) {
		UUID sessionId = sessionIdsByUserId.get(userId);
		if (sessionId == null) {
			return Optional.empty();
		}

		ActiveSession session = sessionsById.get(sessionId);
		if (session == null) {
			throw new IllegalStateException("Active session indexes are inconsistent");
		}
		return Optional.of(session);
	}

	public synchronized void remove(UUID sessionId) {
		ActiveSession removedSession = sessionsById.remove(sessionId);
		if (removedSession != null) {
			sessionIdsByUserId.remove(removedSession.userId(), sessionId);
		}
	}

	public synchronized RemovalResult removeIfOwned(UUID sessionId, Long userId) {
		ActiveSession session = sessionsById.get(sessionId);
		if (session == null) {
			return RemovalResult.NOT_FOUND;
		}
		if (!session.userId().equals(userId)) {
			return RemovalResult.NOT_OWNER;
		}

		sessionsById.remove(sessionId);
		sessionIdsByUserId.remove(userId, sessionId);
		return RemovalResult.REMOVED;
	}
}
