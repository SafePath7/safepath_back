package com.safepath.backend.safety.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.safepath.backend.safety.client.SecurityLightApiClient;
import com.safepath.backend.safety.dto.SecurityLightApiResponse;

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

    private SecurityLightApiResponse response(int pageNo, int totalCount) {
        SecurityLightApiResponse.Item item = new SecurityLightApiResponse.Item(
                "Test light", "1", "", "Test address", "37.5665", "126.9780", "2026", "Pole",
                "", "Institution", "2026-01-01", "1100000", "Test institution");
        return new SecurityLightApiResponse(
                new SecurityLightApiResponse.Header("00", "NORMAL SERVICE."),
                new SecurityLightApiResponse.Body(
                        new SecurityLightApiResponse.Items(List.of(item)), 1_000, pageNo, totalCount));
    }
}
