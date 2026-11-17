package com.safepath.backend.safety.facility.cctv;

import java.util.List;

import com.safepath.backend.safety.dto.SafetyFacilityImportItem;
import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.springframework.stereotype.Service;

@Service
public class CctvSyncService {
    private static final String SOURCE = "CCTV";
    private static final int PAGE_SIZE = 100;

    private final CctvApiClient apiClient;
    private final SafetyFacilitySyncService facilitySyncService;

    public CctvSyncService(CctvApiClient apiClient, SafetyFacilitySyncService facilitySyncService) {
        this.apiClient = apiClient;
        this.facilitySyncService = facilitySyncService;
    }

    public SyncResult syncAll() {
        return syncAll(0);
    }

    public SyncResult syncAll(int maxPages) {
        if (maxPages < 0) {
            throw new IllegalArgumentException("maxPages must not be negative");
        }

        int createdCount = 0;
        int updatedCount = 0;
        int totalCount = 0;

        for (int pageNo = 1; ; pageNo++) {
            CctvApiResponse response = apiClient.fetch(pageNo, PAGE_SIZE);
            validateResponse(response);

            List<CctvApiResponse.Item> apiItems = response.response().body().items().item();
            List<SafetyFacilityImportItem> items = apiItems.stream()
                    .filter(this::hasValidCoordinates)
                    .map(this::toImportItem)
                    .toList();
            SafetyFacilitySyncService.SyncResult pageResult = facilitySyncService.syncAll(items);
            createdCount += pageResult.createdCount();
            updatedCount += pageResult.updatedCount();
            totalCount = response.response().body().totalCount();

            if (maxPages > 0 && pageNo >= maxPages || pageNo * PAGE_SIZE >= totalCount) {
                return new SyncResult(pageNo, totalCount, createdCount, updatedCount);
            }
        }
    }

    private void validateResponse(CctvApiResponse response) {
        if (response.response() == null || response.response().header() == null
                || !"0".equals(response.response().header().resultCode())
                || response.response().body() == null || response.response().body().items() == null
                || response.response().body().items().item() == null) {
            throw new IllegalStateException("CCTV API response is invalid");
        }
    }

    private SafetyFacilityImportItem toImportItem(CctvApiResponse.Item item) {
        return new SafetyFacilityImportItem(
                SOURCE,
                item.managementNumber(),
                Double.parseDouble(item.latitude()),
                Double.parseDouble(item.longitude()),
                firstNonBlank(item.roadAddress(), item.lotNumberAddress()),
                SOURCE);
    }

    private boolean hasValidCoordinates(CctvApiResponse.Item item) {
        return item.managementNumber() != null && !item.managementNumber().isBlank()
                && isValidCoordinate(item.latitude(), -90, 90)
                && isValidCoordinate(item.longitude(), -180, 180);
    }

    private boolean isValidCoordinate(String value, double min, double max) {
        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            double coordinate = Double.parseDouble(value);
            return Double.isFinite(coordinate) && coordinate >= min && coordinate <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback == null ? "" : fallback;
    }

    public record SyncResult(int pageCount, int totalCount, int createdCount, int updatedCount) {
    }
}
