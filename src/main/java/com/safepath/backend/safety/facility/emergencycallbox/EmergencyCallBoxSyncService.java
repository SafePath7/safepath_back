package com.safepath.backend.safety.facility.emergencycallbox;

import java.util.List;

import com.safepath.backend.safety.facility.SafetyFacilityApiRetryExecutor;
import com.safepath.backend.safety.facility.SafetyFacilityApiRetryExhaustedException;
import com.safepath.backend.safety.facility.SafetyFacilityApiRequestException;
import com.safepath.backend.safety.dto.SafetyFacilityImportItem;
import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class EmergencyCallBoxSyncService {
    private static final Logger log = LoggerFactory.getLogger(EmergencyCallBoxSyncService.class);
    private static final String SOURCE = "EMERGENCY_CALL_BOX";
    private static final int PAGE_SIZE = 100;
    private static final long REQUEST_INTERVAL_MILLIS = 400;
    private static final long FAILED_PAGE_RETRY_DELAY_MILLIS = 60_000;

    private final EmergencyCallBoxApiClient apiClient;
    private final SafetyFacilitySyncService facilitySyncService;
    private final SafetyFacilityApiRetryExecutor retryExecutor =
            new SafetyFacilityApiRetryExecutor("Emergency call box", 5, 5_000, 60_000);

    public EmergencyCallBoxSyncService(
            EmergencyCallBoxApiClient apiClient,
            SafetyFacilitySyncService facilitySyncService) {
        this.apiClient = apiClient;
        this.facilitySyncService = facilitySyncService;
    }

    public SyncResult syncAll() {
        return syncAll(0);
    }

    public SyncResult syncAll(int maxPages) {
        return syncAll(1, maxPages);
    }

    public SyncResult syncAll(int startPage, int maxPages) {
        if (startPage < 1) {
            throw new IllegalArgumentException("startPage must be at least 1");
        }
        if (maxPages < 0) {
            throw new IllegalArgumentException("maxPages must not be negative");
        }

        int createdCount = 0;
        int updatedCount = 0;
        int totalCount = 0;

        for (int pageNo = startPage; ; pageNo++) {
            EmergencyCallBoxApiResponse response = fetchPage(pageNo);

            List<EmergencyCallBoxApiResponse.Item> apiItems = response.response().body().items().item();
            List<SafetyFacilityImportItem> items = apiItems.stream()
                    .filter(this::hasValidCoordinates)
                    .map(this::toImportItem)
                    .toList();
            SafetyFacilitySyncService.SyncResult pageResult = facilitySyncService.syncAll(items);
            createdCount += pageResult.createdCount();
            updatedCount += pageResult.updatedCount();
            totalCount = response.response().body().totalCount();

            if ((maxPages > 0 && pageNo >= maxPages) || pageNo * PAGE_SIZE >= totalCount) {
                return new SyncResult(pageNo, totalCount, createdCount, updatedCount);
            }
            SafetyFacilityApiRetryExecutor.waitFor(REQUEST_INTERVAL_MILLIS,
                    "Emergency call box sync was interrupted while waiting for the next page");
        }
    }

    private EmergencyCallBoxApiResponse fetchPage(int pageNo) {
        for (;;) {
            try {
                return retryExecutor.execute(pageNo, () -> {
                    EmergencyCallBoxApiResponse response = apiClient.fetch(pageNo, PAGE_SIZE);
                    validateResponse(pageNo, response);
                    return response;
                });
            } catch (SafetyFacilityApiRetryExhaustedException e) {
                if (!e.isRetryable()) throw e;
                SafetyFacilityApiRetryExecutor.waitFor(FAILED_PAGE_RETRY_DELAY_MILLIS,
                        "Emergency call box sync was interrupted while retrying a failed page");
            }
        }
    }

    private void validateResponse(int pageNo, EmergencyCallBoxApiResponse response) {
        if (response == null || response.response() == null || response.response().header() == null
                || response.response().body() == null) {
            log.error("Emergency call box API response is invalid: resultCode={}", response == null
                    || response.response() == null || response.response().header() == null ? null
                            : response.response().header().resultCode());
            throw SafetyFacilityApiRequestException.emptyResponse("Emergency call box", pageNo,
                    "response, header, or body");
        }
        if (!"0".equals(response.response().header().resultCode()) || response.response().body().items() == null
                || response.response().body().items().item() == null) {
            throw new IllegalStateException("Emergency call box API response is invalid");
        }
    }

    private SafetyFacilityImportItem toImportItem(EmergencyCallBoxApiResponse.Item item) {
        return new SafetyFacilityImportItem(
                SOURCE,
                item.managementNumber(),
                Double.parseDouble(item.latitude()),
                Double.parseDouble(item.longitude()),
                firstNonBlank(item.roadAddress(), item.lotNumberAddress()),
                SOURCE);
    }

    private boolean hasValidCoordinates(EmergencyCallBoxApiResponse.Item item) {
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
