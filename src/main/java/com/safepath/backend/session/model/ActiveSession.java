package com.safepath.backend.session.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ActiveSession(UUID id, Long userId, Instant startedAt) {

	public ActiveSession {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(userId, "userId must not be null");
		Objects.requireNonNull(startedAt, "startedAt must not be null");
	}
}
