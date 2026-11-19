package com.safepath.backend.safety.admin.dto;

import java.util.List;

public record SafetyFacilityAdminListResponse(
        List<SafetyFacilityAdminResponse> facilities,
        long totalCount,
        int page,
        int size) {
}
