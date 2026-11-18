package com.safepath.backend.safety.facility.cctv;

import java.util.List;

import com.safepath.backend.safety.dto.SafetyFacilityImportItem;
import com.safepath.backend.safety.facility.SafetyFacilityApiRetryExecutor;
import com.safepath.backend.safety.facility.SafetyFacilityApiRetryExhaustedException;
import com.safepath.backend.safety.facility.SafetyFacilityApiRequestException;
import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CctvSyncService {
    private static final Logger log = LoggerFactory.getLogger(CctvSyncService.class);
    private static final String SOURCE = "CCTV";
    private static final int PAGE_SIZE = 100;

    private final CctvApiClient apiClient;
    private final SafetyFacilitySyncService facilitySyncService;
    private final SafetyFacilityApiRetryExecutor retryExecutor;
    private final long requestIntervalMillis;
    private final long failedPageRetryDelayMillis;

    public CctvSyncService(CctvApiClient apiClient, SafetyFacilitySyncService facilitySyncService) {
        this(apiClient, facilitySyncService, 5, 5_000, 60_000, 400, 60_000);
    }

    @Autowired
    public CctvSyncService(
            CctvApiClient apiClient,
            SafetyFacilitySyncService facilitySyncService,
            @Value("${safepath.cctv.sync.retry.max-attempts:5}") int maxAttempts,
            @Value("${safepath.cctv.sync.retry.initial-delay-ms:5000}") long initialDelayMillis,
            @Value("${safepath.cctv.sync.retry.max-delay-ms:60000}") long maxDelayMillis,
            @Value("${safepath.cctv.sync.request-interval-ms:400}") long requestIntervalMillis,
            @Value("${safepath.cctv.sync.failed-page-retry-delay-ms:60000}") long failedPageRetryDelayMillis) {
        if (requestIntervalMillis < 0 || failedPageRetryDelayMillis < 0) {
            throw new IllegalArgumentException("CCTV sync delays must not be negative");
        }
        this.apiClient = apiClient;
        this.facilitySyncService = facilitySyncService;
        this.retryExecutor = new SafetyFacilityApiRetryExecutor(SOURCE, maxAttempts, initialDelayMillis, maxDelayMillis);
        this.requestIntervalMillis = requestIntervalMillis;
        this.failedPageRetryDelayMillis = failedPageRetryDelayMillis;
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
            int currentPageNo = pageNo;
            CctvApiResponse response;
            try {
                response = retryExecutor.execute(currentPageNo, () -> {
                    CctvApiResponse fetched = apiClient.fetch(currentPageNo, PAGE_SIZE);
                    validateResponse(currentPageNo, fetched);
                    return fetched;
                });
            } catch (SafetyFacilityApiRetryExhaustedException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                log.error("CCTV API retries exhausted; preserving data and retrying the same page later: page={}, retryInMs={}",
                        currentPageNo, failedPageRetryDelayMillis, e);
                waitBeforeNextRequest(failedPageRetryDelayMillis);
                pageNo--;
                continue;
            }

            if (hasNoItems(response)) {
                int responseTotalCount = response.response().body().totalCount();
                if (isPageAfterLastItem(currentPageNo, responseTotalCount)) {
                    log.info("CCTV sync reached the end of API data: page={}, totalCount={}", currentPageNo, responseTotalCount);
                    return new SyncResult(currentPageNo - 1, responseTotalCount, createdCount, updatedCount);
                }
                log.error("CCTV API response has no items before the expected last page: page={}, totalCount={}, pageSize={}",
                        currentPageNo, responseTotalCount, PAGE_SIZE);
                throw new IllegalStateException("CCTV API response has no items before completion for page " + currentPageNo);
            }

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
            waitBeforeNextRequest(requestIntervalMillis);
        }
    }

    private void validateResponse(int pageNo, CctvApiResponse response) {
        if (response == null || response.response() == null) {
            log.error("CCTV API response is invalid: page={}, missing=response", pageNo);
            throw SafetyFacilityApiRequestException.emptyResponse(SOURCE, pageNo, "response");
        }
        CctvApiResponse.Response apiResponse = response.response();
        if (apiResponse.header() == null) {
            log.error("CCTV API response is invalid: page={}, missing=header", pageNo);
            throw SafetyFacilityApiRequestException.emptyResponse(SOURCE, pageNo, "header");
        }
        if (!"0".equals(apiResponse.header().resultCode())) {
            log.error("CCTV API returned an error: page={}, resultCode={}, resultMsg={}", pageNo,
                    apiResponse.header().resultCode(), apiResponse.header().resultMsg());
            throw new IllegalStateException("CCTV API returned resultCode " + apiResponse.header().resultCode()
                    + " for page " + pageNo);
        }
        if (apiResponse.body() == null) {
            log.error("CCTV API response is invalid: page={}, resultCode={}, resultMsg={}, missing=body", pageNo,
                    apiResponse.header().resultCode(), apiResponse.header().resultMsg());
            throw SafetyFacilityApiRequestException.emptyResponse(SOURCE, pageNo, "body");
        }
    }

    private boolean hasNoItems(CctvApiResponse response) {
        CctvApiResponse.Items items = response.response().body().items();
        return items == null || items.item() == null || items.item().isEmpty();
    }

    private boolean isPageAfterLastItem(int pageNo, int totalCount) {
        return (long) (pageNo - 1) * PAGE_SIZE >= totalCount;
    }

    private void waitBeforeNextRequest(long delayMillis) {
        SafetyFacilityApiRetryExecutor.waitFor(delayMillis,
                "CCTV sync was interrupted while waiting to request the next page");
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
