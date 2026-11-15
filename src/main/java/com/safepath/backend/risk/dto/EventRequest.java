package com.safepath.backend.risk.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EventRequest(@NotNull UUID sessionId,
        @NotBlank @Size(max = 20) String eventType,
        @NotBlank @Size(max = 500) String description, @NotNull Instant detectedAt) {}
