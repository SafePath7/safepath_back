package com.safepath.backend.safety.dto;

import java.util.List;

public record SafetyFacilityMapResponse(
        String mode,
        long totalCount,
        List<SafetyFacilityClusterResponse> clusters,
        List<SafetyFacilityResponse> facilities) {
}
