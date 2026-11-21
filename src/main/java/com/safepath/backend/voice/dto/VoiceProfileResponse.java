package com.safepath.backend.voice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VoiceProfileResponse(boolean registered, Instant consentedAt, Instant createdAt, Instant updatedAt) {}
