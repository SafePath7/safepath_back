package com.safepath.backend.guardian;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public final class GuardianModels {
    private GuardianModels() {}

    public enum Status { PENDING, ACCEPTED, REJECTED, REVOKED, SUSPENDED }
    public enum PermissionType { LOCATION, STATUS, PERSONAL_INFO }

    public record RegistrationRequest(@NotNull @Positive Long guardianId) {}

    public record PermissionRequest(@NotNull Boolean canViewLocation,
            @NotNull Boolean canViewStatus, @NotNull Boolean canViewPersonalInfo) {}

    public record PermissionResponse(boolean allowed) {}

    public record Registration(long id, long userId, long guardianId, Status status,
            Instant requestedAt, Instant acceptedAt, boolean canViewLocation,
            boolean canViewStatus, boolean canViewPersonalInfo, Instant createdAt, Instant updatedAt) {
        public boolean allows(PermissionType type) {
            return status == Status.ACCEPTED && switch (type) {
                case LOCATION -> canViewLocation;
                case STATUS -> canViewStatus;
                case PERSONAL_INFO -> canViewPersonalInfo;
            };
        }
    }
}
