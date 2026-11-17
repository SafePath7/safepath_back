package com.safepath.backend.safety.facility.emergencycallbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.junit.jupiter.api.Test;

class EmergencyCallBoxSyncServiceTest {
    private final EmergencyCallBoxApiClient apiClient = mock(EmergencyCallBoxApiClient.class);
    private final SafetyFacilitySyncService facilitySyncService = mock(SafetyFacilitySyncService.class);
    private final EmergencyCallBoxSyncService service = new EmergencyCallBoxSyncService(apiClient, facilitySyncService);

    @Test
    void fetchesAndSyncsEachApiPage() {
        when(apiClient.fetch(1, 100)).thenReturn(response(1, 101, List.of(item("1", "37.5665", "126.9780"))));
        when(apiClient.fetch(2, 100)).thenReturn(response(2, 101, List.of(item("2", "37.5665", "126.9780"))));
        when(facilitySyncService.syncAll(any())).thenReturn(
                new SafetyFacilitySyncService.SyncResult(1, 0),
                new SafetyFacilitySyncService.SyncResult(0, 1));

        EmergencyCallBoxSyncService.SyncResult result = service.syncAll();

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

        EmergencyCallBoxSyncService.SyncResult result = service.syncAll();

        assertEquals(1, result.createdCount());
        verify(facilitySyncService).syncAll(any());
    }

    @Test
    void stopsAfterConfiguredMaximumPageCount() {
        when(apiClient.fetch(1, 100)).thenReturn(response(1, 1_000, List.of(item("1", "37.5665", "126.9780"))));
        when(facilitySyncService.syncAll(any())).thenReturn(new SafetyFacilitySyncService.SyncResult(1, 0));

        EmergencyCallBoxSyncService.SyncResult result = service.syncAll(1);

        assertEquals(1, result.pageCount());
        assertEquals(1_000, result.totalCount());
    }

    private EmergencyCallBoxApiResponse response(
            int pageNo,
            int totalCount,
            List<EmergencyCallBoxApiResponse.Item> items) {
        return new EmergencyCallBoxApiResponse(new EmergencyCallBoxApiResponse.Response(
                new EmergencyCallBoxApiResponse.Body("JSON", new EmergencyCallBoxApiResponse.Items(items), 100, pageNo,
                        totalCount),
                new EmergencyCallBoxApiResponse.Header("0", "정상")));
    }

    private EmergencyCallBoxApiResponse.Item item(String managementNumber, String latitude, String longitude) {
        return new EmergencyCallBoxApiResponse.Item(managementNumber, latitude, longitude, "도로명 주소", "지번 주소");
    }
}
