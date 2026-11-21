package com.safepath.backend.session.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import com.safepath.backend.session.dto.SelectedRoute;

public record ActiveSession(UUID id, Long userId, Instant startedAt, SelectedRoute selectedRoute, SafetyStatus safetyStatus) {

	public ActiveSession(UUID id, Long userId, Instant startedAt) {
		this(id, userId, startedAt, null, SafetyStatus.UNKNOWN);
	}

	public ActiveSession(UUID id, Long userId, Instant startedAt, SelectedRoute selectedRoute) {
		this(id, userId, startedAt, selectedRoute, SafetyStatus.UNKNOWN);
	}

	public ActiveSession {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(userId, "userId must not be null");
		Objects.requireNonNull(startedAt, "startedAt must not be null");
		Objects.requireNonNull(safetyStatus, "safetyStatus must not be null");
	}
}
