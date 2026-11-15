package com.safepath.backend.guardian.dto;

import jakarta.validation.constraints.NotNull;

public record PermissionRequest(@NotNull Boolean canViewLocation,
        @NotNull Boolean canViewStatus, @NotNull Boolean canViewPersonalInfo) {}
