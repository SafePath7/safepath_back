package com.safepath.backend.guardian.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RegistrationRequest(@NotNull @Positive Long guardianId) {}
