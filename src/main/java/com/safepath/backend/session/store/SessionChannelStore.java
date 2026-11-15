package com.safepath.backend.session.store;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class SessionChannelStore {
	private final ConcurrentMap<UUID, String> channelIdsBySessionId = new ConcurrentHashMap<>();

	public void save(UUID sessionId, String channelId) {
		Objects.requireNonNull(sessionId, "sessionId must not be null");
		if (channelId == null || channelId.isBlank()) {
			throw new IllegalArgumentException("channelId must not be blank");
		}
		if (channelIdsBySessionId.putIfAbsent(sessionId, channelId) != null) {
			throw new IllegalStateException("Session already has a channel");
		}
	}

	public Optional<String> findBySessionId(UUID sessionId) {
		return Optional.ofNullable(channelIdsBySessionId.get(Objects.requireNonNull(sessionId)));
	}

	public Optional<String> remove(UUID sessionId) {
		return Optional.ofNullable(channelIdsBySessionId.remove(Objects.requireNonNull(sessionId)));
	}

	public boolean removeIfMapped(UUID sessionId, String channelId) {
		return channelIdsBySessionId.remove(Objects.requireNonNull(sessionId), Objects.requireNonNull(channelId));
	}
}
