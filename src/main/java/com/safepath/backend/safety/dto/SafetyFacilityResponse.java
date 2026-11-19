package com.safepath.backend.safety.dto;

import com.safepath.backend.safety.entity.SafetyFacility;

public record SafetyFacilityResponse(long id, double latitude, double longitude, String address,
        String facilityType) {
    public static SafetyFacilityResponse from(SafetyFacility facility) {
        return new SafetyFacilityResponse(facility.getId(), facility.getLatitude(), facility.getLongitude(),
                facility.getAddress(), facility.getFacilityType());
    }
}
