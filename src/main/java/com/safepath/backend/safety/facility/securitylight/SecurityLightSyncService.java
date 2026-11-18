package com.safepath.backend.safety.facility.securitylight;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
public class SecurityLightSyncService {
    private static final Logger log = LoggerFactory.getLogger(SecurityLightSyncService.class);
    private static final String SOURCE = "SECURITY_LIGHT";
    private static final int PAGE_SIZE = 1_000;
    private static final long REQUEST_INTERVAL_MILLIS = 400;
    private static final long FAILED_PAGE_RETRY_DELAY_MILLIS = 60_000;

    private final SecurityLightApiClient apiClient;
    private final SafetyFacilitySyncService facilitySyncService;
    private final SafetyFacilityApiRetryExecutor retryExecutor =
            new SafetyFacilityApiRetryExecutor("Security light", 5, 5_000, 60_000);

    public SecurityLightSyncService(SecurityLightApiClient apiClient, SafetyFacilitySyncService facilitySyncService) {
        this.apiClient = apiClient;
        this.facilitySyncService = facilitySyncService;
    }

    public SyncResult syncAll() {
        int createdCount = 0;
        int updatedCount = 0;
        int totalCount = 0;

        for (int pageNo = 1; ; pageNo++) {
            SecurityLightApiResponse response = fetchPage(pageNo);
            List<SecurityLightApiResponse.Item> apiItems = response.body().items().item();
            List<SafetyFacilityImportItem> items = apiItems.stream()
                    .filter(this::hasValidCoordinates)
                    .map(this::toImportItem)
                    .toList();
            SafetyFacilitySyncService.SyncResult pageResult = facilitySyncService.syncAll(items);
            createdCount += pageResult.createdCount();
            updatedCount += pageResult.updatedCount();
            totalCount = response.body().totalCount();

            if (pageNo * PAGE_SIZE >= totalCount) {
                return new SyncResult(pageNo, totalCount, createdCount, updatedCount);
            }
            SafetyFacilityApiRetryExecutor.waitFor(REQUEST_INTERVAL_MILLIS,
                    "Security light sync was interrupted while waiting for the next page");
        }
    }

    private SecurityLightApiResponse fetchPage(int pageNo) {
        for (;;) {
            try {
                return retryExecutor.execute(pageNo, () -> {
                    SecurityLightApiResponse response = apiClient.fetch(pageNo, PAGE_SIZE);
                    validateResponse(pageNo, response);
                    return response;
                });
            } catch (SafetyFacilityApiRetryExhaustedException e) {
                if (!e.isRetryable()) throw e;
                SafetyFacilityApiRetryExecutor.waitFor(FAILED_PAGE_RETRY_DELAY_MILLIS,
                        "Security light sync was interrupted while retrying a failed page");
            }
        }
    }

    private void validateResponse(int pageNo, SecurityLightApiResponse response) {
        if (response == null || response.header() == null || response.body() == null) {
            log.error("Security light API response is invalid: resultCode={}",
                    response == null || response.header() == null ? null : response.header().resultCode());
            throw SafetyFacilityApiRequestException.emptyResponse("Security light", pageNo, "response, header, or body");
        }
        if (!"00".equals(response.header().resultCode()) || response.body().items() == null
                || response.body().items().item() == null) {
            throw new IllegalStateException("Security light API response is invalid");
        }
    }

    private SafetyFacilityImportItem toImportItem(SecurityLightApiResponse.Item item) {
        String address = item.rdnmadr().isBlank() ? item.lnmadr() : item.rdnmadr();
        return new SafetyFacilityImportItem(
                SOURCE,
                externalId(item),
                Double.parseDouble(item.latitude()),
                Double.parseDouble(item.longitude()),
                address,
                SOURCE);
    }

    private boolean hasValidCoordinates(SecurityLightApiResponse.Item item) {
        return isValidCoordinate(item.latitude(), -90, 90)
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

    private String externalId(SecurityLightApiResponse.Item item) {
        String value = String.join("|", item.insttCode(), item.lmpLcNm(), item.latitude(), item.longitude());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record SyncResult(int pageCount, int totalCount, int createdCount, int updatedCount) {
    }
}
