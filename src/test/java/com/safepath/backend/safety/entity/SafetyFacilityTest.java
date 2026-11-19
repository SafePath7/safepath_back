package com.safepath.backend.safety.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class SafetyFacilityTest {
    @Test
    void createsFacilityWithSourceIdentifierAndTimestamps() {
        SafetyFacility facility = SafetyFacility.create(
                "CCTV", "CCTV-100", 37.5665, 126.9780, "Seoul", "CCTV");

        assertEquals("CCTV", facility.getSource());
        assertEquals("CCTV-100", facility.getExternalId());
        assertEquals(37.5665, facility.getLatitude());
        assertEquals(126.9780, facility.getLongitude());
        assertEquals("Seoul", facility.getAddress());
        assertEquals("CCTV", facility.getFacilityType());
        assertNotNull(facility.getCreatedAt());
        assertEquals(facility.getCreatedAt(), facility.getUpdatedAt());
    }

    @Test
    void updatesImportedFacilityData() {
        SafetyFacility facility = SafetyFacility.create(
                "CCTV", "CCTV-100", 37.5665, 126.9780, "Old address", "CCTV");

        facility.update(37.5670, 126.9790, "New address", "SECURITY_CAMERA");

        assertEquals(37.5670, facility.getLatitude());
        assertEquals(126.9790, facility.getLongitude());
        assertEquals("New address", facility.getAddress());
        assertEquals("SECURITY_CAMERA", facility.getFacilityType());
        assertNotNull(facility.getUpdatedAt());
    }
}
