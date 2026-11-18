package com.safepath.backend.safety.facility.cctv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.safepath.backend.safety.facility.SafetyFacilityApiRequestException;
import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.junit.jupiter.api.Test;

class CctvSyncServiceTest {
    private final CctvApiClient apiClient = mock(CctvApiClient.class);
    private final SafetyFacilitySyncService facilitySyncService = mock(SafetyFacilitySyncService.class);
    private final CctvSyncService service = new CctvSyncService(apiClient, facilitySyncService);

    @Test
    void fetchesAndSyncsEachApiPage() {
        when(apiClient.fetch(1, 100)).thenReturn(response(1, 101, List.of(item("1", "37.5665", "126.9780"))));
        when(apiClient.fetch(2, 100)).thenReturn(response(2, 101, List.of(item("2", "37.5665", "126.9780"))));
        when(facilitySyncService.syncAll(any())).thenReturn(
                new SafetyFacilitySyncService.SyncResult(1, 0),
                new SafetyFacilitySyncService.SyncResult(0, 1));

        CctvSyncService.SyncResult result = service.syncAll();

        assertEquals(2, result.pageCount());
        assertEquals(101, result.totalCount());
        assertEquals(1, result.createdCount());
        assertEquals(1, result.updatedCount());
        verify(facilitySyncService, times(2)).syncAll(any());
    }

    @Test
    void skipsItemsWithMissingCoordinates() {
        when(apiClient.fetch(1, 100)).thenReturn(response(1, 2, List.of(
                item("1", "37.5665", "126.9780"),
                item("2", "", "126.9780"))));
        when(facilitySyncService.syncAll(any())).thenReturn(new SafetyFacilitySyncService.SyncResult(1, 0));

        CctvSyncService.SyncResult result = service.syncAll();

        assertEquals(1, result.createdCount());
        verify(facilitySyncService).syncAll(any());
    }

    @Test
    void stopsAfterConfiguredMaximumPageCount() {
        when(apiClient.fetch(1, 100)).thenReturn(response(1, 1_000, List.of(item("1", "37.5665", "126.9780"))));
        when(facilitySyncService.syncAll(any())).thenReturn(new SafetyFacilitySyncService.SyncResult(1, 0));

        CctvSyncService.SyncResult result = service.syncAll(1);

        assertEquals(1, result.pageCount());
        assertEquals(1_000, result.totalCount());
        verify(facilitySyncService).syncAll(any());
    }

    @Test
    void retriesAnIncompleteResponseForTheSamePage() {
        CctvSyncService retryingService = new CctvSyncService(
                apiClient, facilitySyncService, 2, 0, 0, 0, 0);
        when(apiClient.fetch(1, 100)).thenReturn(new CctvApiResponse(null))
                .thenReturn(response(1, 1, List.of(item("1", "37.5665", "126.9780"))));
        when(facilitySyncService.syncAll(any())).thenReturn(new SafetyFacilitySyncService.SyncResult(1, 0));

        CctvSyncService.SyncResult result = retryingService.syncAll();

        assertEquals(1, result.createdCount());
        verify(apiClient, times(2)).fetch(1, 100);
    }

    @Test
    void startsFromConfiguredPage() {
        when(apiClient.fetch(666, 100)).thenReturn(response(666, 66_600, List.of(item("666", "37.5665", "126.9780"))));
        when(facilitySyncService.syncAll(any())).thenReturn(new SafetyFacilitySyncService.SyncResult(1, 0));

        CctvSyncService.SyncResult result = service.syncAll(666, 0);

        assertEquals(666, result.pageCount());
        verify(apiClient).fetch(666, 100);
    }

    @Test
    void completesNormallyWhenStartPageIsAfterLastApiPage() {
        when(apiClient.fetch(2, 100)).thenReturn(response(2, 100, List.of()));

        CctvSyncService.SyncResult result = service.syncAll(2, 0);

        assertEquals(1, result.pageCount());
        assertEquals(100, result.totalCount());
        verify(facilitySyncService, never()).syncAll(any());
    }

    @Test
    void failsWhenItemsAreMissingBeforeLastApiPage() {
        when(apiClient.fetch(2, 100)).thenReturn(response(2, 201, List.of()));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.syncAll(2, 0));

        assertEquals("CCTV API response has no items before completion for page 2", exception.getMessage());
    }

    @Test
    void retriesTheSamePageLaterAfterTransientRetriesAreExhausted() {
        CctvSyncService retryingService = new CctvSyncService(
                apiClient, facilitySyncService, 1, 0, 0, 0, 0);
        when(apiClient.fetch(1, 100)).thenThrow(
                SafetyFacilityApiRequestException.httpFailure("CCTV", 1, 503, "Service Unavailable", null))
                .thenReturn(response(1, 1, List.of(item("1", "37.5665", "126.9780"))));
        when(facilitySyncService.syncAll(any())).thenReturn(new SafetyFacilitySyncService.SyncResult(1, 0));

        CctvSyncService.SyncResult result = retryingService.syncAll();

        assertEquals(1, result.createdCount());
        verify(apiClient, times(2)).fetch(1, 100);
    }

    private CctvApiResponse response(int pageNo, int totalCount, List<CctvApiResponse.Item> items) {
        return new CctvApiResponse(new CctvApiResponse.Response(
                new CctvApiResponse.Body("JSON", new CctvApiResponse.Items(items), 100, pageNo, totalCount),
                new CctvApiResponse.Header("0", "정상")));
    }

    private CctvApiResponse.Item item(String managementNumber, String latitude, String longitude) {
        return new CctvApiResponse.Item(managementNumber, latitude, longitude, "도로명 주소", "지번 주소");
    }
}
