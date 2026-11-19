package com.safepath.backend.safety.dto;

public record SafetyFacilityImportItem(
        String source,
        String externalId,
        double latitude,
        double longitude,
        String address,
        String facilityType) {
}
