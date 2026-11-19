package com.safepath.backend.guardian.dto;

import java.time.Instant;

import com.safepath.backend.common.util.UtcTime;
import com.safepath.backend.guardian.entity.GuardianRegistration;
import com.safepath.backend.guardian.entity.RegistrationStatus;

/** A registration for list screens, with both members' names so no extra lookup is needed. */
public record RegistrationSummaryResponse(long id, long userId, String userName, long guardianId,
        String guardianName, RegistrationStatus status, Instant requestedAt, Instant acceptedAt,
        boolean canViewLocation, boolean canViewStatus, boolean canViewPersonalInfo) {
    public static RegistrationSummaryResponse of(GuardianRegistration registration,
            String userName, String guardianName) {
        return new RegistrationSummaryResponse(registration.getId(), registration.getUserId(), userName,
                registration.getGuardianId(), guardianName, registration.getStatus(),
                UtcTime.toInstant(registration.getRequestedAt()), UtcTime.toInstant(registration.getAcceptedAt()),
                registration.isCanViewLocation(), registration.isCanViewLocation(),
                registration.isCanViewPersonalInfo());
    }
}
