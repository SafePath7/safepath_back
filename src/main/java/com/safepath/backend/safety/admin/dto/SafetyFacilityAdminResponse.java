package com.safepath.backend.safety.admin.dto;

import java.time.LocalDateTime;

import com.safepath.backend.safety.entity.SafetyFacility;

public record SafetyFacilityAdminResponse(
        long id,
        double latitude,
        double longitude,
        String address,
        String facilityType,
        String source,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public static SafetyFacilityAdminResponse from(SafetyFacility facility) {
        return new SafetyFacilityAdminResponse(
                facility.getId(), facility.getLatitude(), facility.getLongitude(), facility.getAddress(),
                facility.getFacilityType(), facility.getSource(), facility.getCreatedAt(), facility.getUpdatedAt());
    }
}
