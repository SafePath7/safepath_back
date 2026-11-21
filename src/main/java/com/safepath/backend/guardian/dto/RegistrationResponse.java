package com.safepath.backend.guardian.dto;

import java.time.Instant;

import com.safepath.backend.common.util.UtcTime;
import com.safepath.backend.guardian.entity.GuardianRegistration;
import com.safepath.backend.guardian.entity.RegistrationStatus;

public record RegistrationResponse(long id, long userId, long guardianId, RegistrationStatus status,
        Instant requestedAt, Instant acceptedAt, boolean canViewLocation,
        boolean canViewStatus, boolean canViewPersonalInfo, Instant createdAt, Instant updatedAt) {
    public static RegistrationResponse from(GuardianRegistration registration) {
        return new RegistrationResponse(registration.getId(), registration.getUserId(),
                registration.getGuardianId(), registration.getStatus(),
                UtcTime.toInstant(registration.getRequestedAt()), UtcTime.toInstant(registration.getAcceptedAt()),
                registration.isCanViewLocation(), registration.isCanViewLocation(),
                registration.isCanViewPersonalInfo(), UtcTime.toInstant(registration.getCreatedAt()),
                UtcTime.toInstant(registration.getUpdatedAt()));
    }
}
