package com.safepath.backend.safety.facility.safetyguardianstore;

import java.util.List;

import com.safepath.backend.safety.dto.SafetyFacilityImportItem;
import com.safepath.backend.safety.facility.SafetyFacilityApiRetryExecutor;
import com.safepath.backend.safety.facility.exception.SafetyFacilityApiRetryExhaustedException;
import com.safepath.backend.safety.facility.exception.SafetyFacilityApiRequestException;
import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class SafetyGuardianStoreSyncService {
    private static final Logger log = LoggerFactory.getLogger(SafetyGuardianStoreSyncService.class);
    private static final String SOURCE = "SAFETY_GUARDIAN_STORE";
    private static final int PAGE_SIZE = 100;
    private static final long REQUEST_INTERVAL_MILLIS = 400;
    private static final long FAILED_PAGE_RETRY_DELAY_MILLIS = 60_000;

    private final SafetyGuardianStoreApiClient apiClient;
    private final SafetyFacilitySyncService facilitySyncService;
    private final SafetyFacilityApiRetryExecutor retryExecutor =
            new SafetyFacilityApiRetryExecutor("Safety guardian store", 5, 5_000, 60_000);

    public SafetyGuardianStoreSyncService(
            SafetyGuardianStoreApiClient apiClient,
            SafetyFacilitySyncService facilitySyncService) {
        this.apiClient = apiClient;
        this.facilitySyncService = facilitySyncService;
    }

    public SyncResult syncAll(int maxPages) {
        if (maxPages < 0) {
            throw new IllegalArgumentException("maxPages must not be negative");
        }

        int createdCount = 0;
        int updatedCount = 0;
        int totalCount = 0;

        for (int pageNo = 1; ; pageNo++) {
            SafetyGuardianStoreApiResponse response = fetchPage(pageNo);
            List<SafetyGuardianStoreApiResponse.Item> apiItems = response.body().items().item();
            List<SafetyFacilityImportItem> items = apiItems.stream()
                    .filter(this::hasValidCoordinates)
                    .map(this::toImportItem)
                    .toList();
            SafetyFacilitySyncService.SyncResult pageResult = facilitySyncService.syncAll(items);
            createdCount += pageResult.createdCount();
            updatedCount += pageResult.updatedCount();
            totalCount = response.body().totalCount();

            if (maxPages > 0 && pageNo >= maxPages || pageNo * PAGE_SIZE >= totalCount) {
                return new SyncResult(pageNo, totalCount, createdCount, updatedCount);
            }
            SafetyFacilityApiRetryExecutor.waitFor(REQUEST_INTERVAL_MILLIS,
                    "Safety guardian store sync was interrupted while waiting for the next page");
        }
    }

    private SafetyGuardianStoreApiResponse fetchPage(int pageNo) {
        for (;;) {
            try {
                return retryExecutor.execute(pageNo, () -> {
                    SafetyGuardianStoreApiResponse response = apiClient.fetch(pageNo, PAGE_SIZE);
                    validateResponse(pageNo, response);
                    return response;
                });
            } catch (SafetyFacilityApiRetryExhaustedException e) {
                if (!e.isRetryable()) throw e;
                SafetyFacilityApiRetryExecutor.waitFor(FAILED_PAGE_RETRY_DELAY_MILLIS,
                        "Safety guardian store sync was interrupted while retrying a failed page");
            }
        }
    }

    private void validateResponse(int pageNo, SafetyGuardianStoreApiResponse response) {
        if (response == null || response.header() == null || response.body() == null) {
            log.error("Safety guardian store API response is invalid: resultCode={}",
                    response == null || response.header() == null ? null : response.header().resultCode());
            throw SafetyFacilityApiRequestException.emptyResponse("Safety guardian store", pageNo, "response, header, or body");
        }
        if (!"00".equals(response.header().resultCode()) || response.body().items() == null
                || response.body().items().item() == null) {
            throw new IllegalStateException("Safety guardian store API response is invalid");
        }
    }

    private SafetyFacilityImportItem toImportItem(SafetyGuardianStoreApiResponse.Item item) {
        return new SafetyFacilityImportItem(
                SOURCE,
                externalId(item),
                Double.parseDouble(item.latitude()),
                Double.parseDouble(item.longitude()),
                firstNonBlank(item.roadAddress(), item.lotAddress()),
                SOURCE);
    }

    private boolean hasValidCoordinates(SafetyGuardianStoreApiResponse.Item item) {
        return isValidCoordinate(item.latitude(), -90, 90)
                && isValidCoordinate(item.longitude(), -180, 180);
    }

    private boolean isValidCoordinate(String value, double min, double max) {
        try {
            double coordinate = Double.parseDouble(value);
            return Double.isFinite(coordinate) && coordinate >= min && coordinate <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String externalId(SafetyGuardianStoreApiResponse.Item item) {
        return String.join("|", nullToEmpty(item.storeName()), nullToEmpty(item.phoneNumber()),
                firstNonBlank(item.roadAddress(), item.lotAddress()));
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : nullToEmpty(fallback);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record SyncResult(int pageCount, int totalCount, int createdCount, int updatedCount) {
    }
}
