package com.safepath.backend.session.dto;

import com.safepath.backend.session.model.ActiveSession;

import java.time.Instant;
import java.util.UUID;

public record ActiveSessionResponse(UUID id, Instant startedAt) {

	public static ActiveSessionResponse from(ActiveSession session) {
		return new ActiveSessionResponse(session.id(), session.startedAt());
	}
}
