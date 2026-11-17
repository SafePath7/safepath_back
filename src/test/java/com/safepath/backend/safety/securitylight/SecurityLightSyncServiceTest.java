package com.safepath.backend.safety.securitylight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.junit.jupiter.api.Test;

class SecurityLightSyncServiceTest {
    private final SecurityLightApiClient apiClient = mock(SecurityLightApiClient.class);
    private final SafetyFacilitySyncService facilitySyncService = mock(SafetyFacilitySyncService.class);
    private final SecurityLightSyncService service = new SecurityLightSyncService(apiClient, facilitySyncService);

    @Test
    void fetchesAndSyncsEachApiPage() {
        when(apiClient.fetch(1, 1_000)).thenReturn(response(1, 1_001));
        when(apiClient.fetch(2, 1_000)).thenReturn(response(2, 1_001));
        when(facilitySyncService.syncAll(any())).thenReturn(
                new SafetyFacilitySyncService.SyncResult(1, 0),
                new SafetyFacilitySyncService.SyncResult(0, 1));

        SecurityLightSyncService.SyncResult result = service.syncAll();

        assertEquals(2, result.pageCount());
        assertEquals(1_001, result.totalCount());
        assertEquals(1, result.createdCount());
        assertEquals(1, result.updatedCount());
        verify(facilitySyncService, times(2)).syncAll(any());
    }

    @Test
    void skipsItemsWithMissingCoordinates() {
        SecurityLightApiResponse.Item validItem = item("37.5665", "126.9780");
        SecurityLightApiResponse.Item missingLatitudeItem = item("", "126.9780");
        when(apiClient.fetch(1, 1_000)).thenReturn(response(1, 2, List.of(validItem, missingLatitudeItem)));
        when(facilitySyncService.syncAll(any())).thenReturn(new SafetyFacilitySyncService.SyncResult(1, 0));

        SecurityLightSyncService.SyncResult result = service.syncAll();

        assertEquals(1, result.createdCount());
        verify(facilitySyncService).syncAll(any());
    }

    private SecurityLightApiResponse response(int pageNo, int totalCount) {
        return response(pageNo, totalCount, List.of(item("37.5665", "126.9780")));
    }

    private SecurityLightApiResponse response(int pageNo, int totalCount, List<SecurityLightApiResponse.Item> items) {
        return new SecurityLightApiResponse(
                new SecurityLightApiResponse.Header("00", "NORMAL SERVICE."),
                new SecurityLightApiResponse.Body(
                        new SecurityLightApiResponse.Items(items), 1_000, pageNo, totalCount));
    }

    private SecurityLightApiResponse.Item item(String latitude, String longitude) {
        return new SecurityLightApiResponse.Item(
                "Test light", "1", "", "Test address", latitude, longitude, "2026", "Pole",
                "", "Institution", "2026-01-01", "1100000", "Test institution");
    }
}
