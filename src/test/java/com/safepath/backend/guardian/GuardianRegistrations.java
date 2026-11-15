package com.safepath.backend.guardian;

import java.time.LocalDateTime;

import com.safepath.backend.guardian.entity.GuardianRegistration;
import com.safepath.backend.guardian.entity.RegistrationStatus;

import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** Builds entities for unit tests; production rows only ever come from the database. */
final class GuardianRegistrations {
    private GuardianRegistrations() {}

    static GuardianRegistration of(long id, long userId, long guardianId, RegistrationStatus status,
            boolean location, boolean viewStatus) {
        GuardianRegistration registration = BeanUtils.instantiateClass(GuardianRegistration.class);
        LocalDateTime now = LocalDateTime.parse("2026-11-15T00:00:00");
        ReflectionTestUtils.setField(registration, "id", id);
        ReflectionTestUtils.setField(registration, "userId", userId);
        ReflectionTestUtils.setField(registration, "guardianId", guardianId);
        ReflectionTestUtils.setField(registration, "status", status);
        ReflectionTestUtils.setField(registration, "requestedAt", now);
        ReflectionTestUtils.setField(registration, "canViewLocation", location);
        ReflectionTestUtils.setField(registration, "canViewStatus", viewStatus);
        ReflectionTestUtils.setField(registration, "createdAt", now);
        ReflectionTestUtils.setField(registration, "updatedAt", now);
        return registration;
    }
}
