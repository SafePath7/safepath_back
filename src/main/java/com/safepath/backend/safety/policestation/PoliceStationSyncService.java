package com.safepath.backend.safety.policestation;

import java.util.List;

import com.safepath.backend.safety.dto.SafetyFacilityImportItem;
import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PoliceStationSyncService {
    private static final Logger log = LoggerFactory.getLogger(PoliceStationSyncService.class);
    private static final String SOURCE = "POLICE_STATION";
    private static final String FACILITY_TYPE = "POLICE";
    private static final int PAGE_SIZE = 100;
    private static final double WEB_MERCATOR_RADIUS = 6_378_137.0;
    private static final double MAX_WEB_MERCATOR_COORDINATE = 20_037_508.342789244;

    private final PoliceStationApiClient apiClient;
    private final SafetyFacilitySyncService facilitySyncService;

    public PoliceStationSyncService(PoliceStationApiClient apiClient, SafetyFacilitySyncService facilitySyncService) {
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
            PoliceStationApiResponse response = apiClient.fetch(pageNo, PAGE_SIZE);
            validateResponse(response);

            List<PoliceStationApiResponse.Item> apiItems = response.body().items().item();
            List<SafetyFacilityImportItem> items = apiItems.stream()
                    .filter(this::hasValidCoordinates)
                    .map(this::toImportItem)
                    .toList();
            int skippedCount = apiItems.size() - items.size();
            if (skippedCount > 0) {
                log.warn("Police station sync skipped {} items with invalid coordinates on page {}", skippedCount, pageNo);
            }

            SafetyFacilitySyncService.SyncResult pageResult = facilitySyncService.syncAll(items);
            createdCount += pageResult.createdCount();
            updatedCount += pageResult.updatedCount();
            totalCount = response.body().totalCount();

            if (maxPages > 0 && pageNo >= maxPages || pageNo * PAGE_SIZE >= totalCount) {
                return new SyncResult(pageNo, totalCount, createdCount, updatedCount);
            }
        }
    }

    private void validateResponse(PoliceStationApiResponse response) {
        if (response.header() == null || !"00".equals(response.header().resultCode())
                || response.body() == null || response.body().items() == null
                || response.body().items().item() == null) {
            throw new IllegalStateException("Police station API response is invalid");
        }
    }

    private SafetyFacilityImportItem toImportItem(PoliceStationApiResponse.Item item) {
        Coordinate coordinate = toWgs84(item);
        return new SafetyFacilityImportItem(
                SOURCE,
                item.objectId(),
                coordinate.latitude(),
                coordinate.longitude(),
                firstNonBlank(item.roadAddress(), item.address()),
                FACILITY_TYPE);
    }

    private boolean hasValidCoordinates(PoliceStationApiResponse.Item item) {
        if (item.objectId() == null || item.objectId().isBlank()
                || !isValidWebMercatorCoordinate(item.longitude())
                || !isValidWebMercatorCoordinate(item.latitude())) {
            return false;
        }

        Coordinate coordinate = toWgs84(item);
        return Double.isFinite(coordinate.latitude()) && Double.isFinite(coordinate.longitude())
                && coordinate.latitude() >= -90 && coordinate.latitude() <= 90
                && coordinate.longitude() >= -180 && coordinate.longitude() <= 180;
    }

    private Coordinate toWgs84(PoliceStationApiResponse.Item item) {
        double x = Double.parseDouble(item.longitude());
        double y = Double.parseDouble(item.latitude());
        double longitude = Math.toDegrees(x / WEB_MERCATOR_RADIUS);
        double latitude = Math.toDegrees(2 * Math.atan(Math.exp(y / WEB_MERCATOR_RADIUS)) - Math.PI / 2);
        return new Coordinate(latitude, longitude);
    }

    private boolean isValidWebMercatorCoordinate(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            double coordinate = Double.parseDouble(value);
            return Double.isFinite(coordinate) && Math.abs(coordinate) <= MAX_WEB_MERCATOR_COORDINATE;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback == null ? "" : fallback;
    }

    public record SyncResult(int pageCount, int totalCount, int createdCount, int updatedCount) {
    }

    private record Coordinate(double latitude, double longitude) {
    }
}
