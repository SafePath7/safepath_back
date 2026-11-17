package com.safepath.backend.safety.safetyguardianstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.junit.jupiter.api.Test;

class SafetyGuardianStoreSyncServiceTest {
    private final SafetyGuardianStoreApiClient apiClient = mock(SafetyGuardianStoreApiClient.class);
    private final SafetyFacilitySyncService facilitySyncService = mock(SafetyFacilitySyncService.class);
    private final SafetyGuardianStoreSyncService service =
            new SafetyGuardianStoreSyncService(apiClient, facilitySyncService);

    @Test
    void fetchesAndSyncsEachApiPage() {
        when(apiClient.fetch(1, 100)).thenReturn(response(101, List.of(item("Store A", "37.5665", "126.9780"))));
        when(apiClient.fetch(2, 100)).thenReturn(response(101, List.of(item("Store B", "37.5651", "126.9895"))));
        when(facilitySyncService.syncAll(any())).thenReturn(
                new SafetyFacilitySyncService.SyncResult(1, 0),
                new SafetyFacilitySyncService.SyncResult(0, 1));

        SafetyGuardianStoreSyncService.SyncResult result = service.syncAll(0);

        assertEquals(2, result.pageCount());
        assertEquals(101, result.totalCount());
        assertEquals(1, result.createdCount());
        assertEquals(1, result.updatedCount());
        verify(facilitySyncService, times(2)).syncAll(argThat(items ->
                items.getFirst().source().equals("SAFETY_GUARDIAN_STORE")
                        && items.getFirst().facilityType().equals("SAFETY_GUARDIAN_STORE")));
    }

    @Test
    void skipsItemsWithInvalidCoordinates() {
        when(apiClient.fetch(1, 100)).thenReturn(response(2, List.of(
                item("Store A", "37.5665", "126.9780"),
                item("Store B", "", "126.9780"))));
        when(facilitySyncService.syncAll(any())).thenReturn(new SafetyFacilitySyncService.SyncResult(1, 0));

        SafetyGuardianStoreSyncService.SyncResult result = service.syncAll(0);

        assertEquals(1, result.createdCount());
        verify(facilitySyncService).syncAll(argThat(items -> items.size() == 1));
    }

    private SafetyGuardianStoreApiResponse response(int totalCount, List<SafetyGuardianStoreApiResponse.Item> items) {
        return new SafetyGuardianStoreApiResponse(
                new SafetyGuardianStoreApiResponse.Header("00", "OK"),
                new SafetyGuardianStoreApiResponse.Body(new SafetyGuardianStoreApiResponse.Items(items), totalCount));
    }

    private SafetyGuardianStoreApiResponse.Item item(String storeName, String latitude, String longitude) {
        return new SafetyGuardianStoreApiResponse.Item(
                storeName, "Road address", "Lot address", latitude, longitude, "02-1234-5678");
    }
}
