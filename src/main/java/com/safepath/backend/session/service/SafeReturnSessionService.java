package com.safepath.backend.session.service;

import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import com.safepath.backend.session.store.ActiveSessionStore.RemovalResult;
import com.safepath.backend.session.store.SessionChannelStore;
import com.safepath.backend.session.store.LatestLocationStore;
import com.safepath.backend.session.exception.SessionChannelException;
import com.safepath.backend.websocket.InMemoryChannelRegistry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;

@Service
public class SafeReturnSessionService {

	private final ActiveSessionStore activeSessionStore;
	private final SelectedGuardianStore selectedGuardianStore;
	private final SessionChannelStore sessionChannelStore;
	private final InMemoryChannelRegistry channelRegistry;
	private final LatestLocationStore latestLocationStore;

	public SafeReturnSessionService(ActiveSessionStore activeSessionStore, SelectedGuardianStore selectedGuardianStore,
			SessionChannelStore sessionChannelStore, InMemoryChannelRegistry channelRegistry,
			LatestLocationStore latestLocationStore) {
		this.activeSessionStore = activeSessionStore;
		this.selectedGuardianStore = selectedGuardianStore;
		this.sessionChannelStore = sessionChannelStore;
		this.channelRegistry = channelRegistry;
		this.latestLocationStore = latestLocationStore;
	}

	public ActiveSession startSession(Long userId) {
		if (userId == null || userId <= 0) {
			throw new IllegalArgumentException("userId must be a positive number");
		}

		ActiveSession session = new ActiveSession(UUID.randomUUID(), userId, Instant.now());
		synchronized (activeSessionStore) {
			activeSessionStore.saveSession(session);
			String channelId = null;
			try {
				channelId = channelRegistry.open(userId, Set.of());
				sessionChannelStore.save(session.id(), channelId);
			} catch (RuntimeException failure) {
				if (channelId != null) {
					try {
						channelRegistry.close(channelId);
					} catch (RuntimeException cleanupFailure) {
						failure.addSuppressed(cleanupFailure);
						// Keep the session and channel ID so endSession can retry cleanup.
						if (sessionChannelStore.findBySessionId(session.id()).isEmpty()) {
							try {
								sessionChannelStore.save(session.id(), channelId);
							} catch (RuntimeException preservationFailure) {
								failure.addSuppressed(preservationFailure);
							}
						}
						throw new SessionChannelException("Channel rollback failed", failure);
					}
					sessionChannelStore.removeIfMapped(session.id(), channelId);
				}
				activeSessionStore.removeIfOwned(session.id(), userId);
				throw new SessionChannelException("Session channel creation failed", failure);
			}
		}
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

	public void endSession(UUID sessionId, Long userId) {
		if (sessionId == null) {
			throw new IllegalArgumentException("sessionId must not be null");
		}
		if (userId == null || userId <= 0) {
			throw new IllegalArgumentException("userId must be a positive number");
		}

		synchronized (activeSessionStore) {
			getSessionStatus(sessionId, userId);
			sessionChannelStore.findBySessionId(sessionId).ifPresent(channelId -> {
				try {
					channelRegistry.close(channelId);
				} catch (RuntimeException failure) {
					throw new SessionChannelException("Session channel cleanup failed", failure);
				}
				sessionChannelStore.removeIfMapped(sessionId, channelId);
			});
			RemovalResult result = activeSessionStore.removeIfOwned(sessionId, userId);
			switch (result) {
				case REMOVED -> {
					selectedGuardianStore.remove(sessionId);
					latestLocationStore.remove(sessionId);
				}
				case NOT_FOUND -> throw new NoSuchElementException("Active session not found");
				case NOT_OWNER -> throw new SecurityException("User is not the owner of this active session");
			}
		}
	}
}
