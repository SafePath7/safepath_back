package com.safepath.backend.session.dto;

import java.time.Instant;
import java.util.UUID;

public record StartSessionResponse(UUID id, Instant startedAt, String channelId) {
}
