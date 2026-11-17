package com.safepath.backend.safety.facility.policestation;

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

class PoliceStationSyncServiceTest {
    private final PoliceStationApiClient apiClient = mock(PoliceStationApiClient.class);
    private final SafetyFacilitySyncService facilitySyncService = mock(SafetyFacilitySyncService.class);
    private final PoliceStationSyncService service = new PoliceStationSyncService(apiClient, facilitySyncService);

    @Test
    void fetchesAndSyncsEachApiPage() {
        when(apiClient.fetch(1, 100)).thenReturn(response(1, 101, List.of(item("1", "37.5665", "126.9780"))));
        when(apiClient.fetch(2, 100)).thenReturn(response(2, 101, List.of(item("2", "37.5665", "126.9780"))));
        when(facilitySyncService.syncAll(any())).thenReturn(
                new SafetyFacilitySyncService.SyncResult(1, 0),
                new SafetyFacilitySyncService.SyncResult(0, 1));

        PoliceStationSyncService.SyncResult result = service.syncAll(0);

        assertEquals(2, result.pageCount());
        assertEquals(101, result.totalCount());
        assertEquals(1, result.createdCount());
        assertEquals(1, result.updatedCount());
        verify(facilitySyncService, times(2)).syncAll(argThat(items -> items.getFirst().source().equals("POLICE_STATION")
                && items.getFirst().facilityType().equals("POLICE")
                && Math.abs(items.getFirst().latitude() - 37.5665) < 0.000001
                && Math.abs(items.getFirst().longitude() - 126.9780) < 0.000001));
    }

    @Test
    void skipsItemsWithMissingCoordinates() {
        when(apiClient.fetch(1, 100)).thenReturn(response(1, 2, List.of(
                item("1", "37.5665", "126.9780"),
                item("2", "", "126.9780"))));
        when(facilitySyncService.syncAll(any())).thenReturn(new SafetyFacilitySyncService.SyncResult(1, 0));

        PoliceStationSyncService.SyncResult result = service.syncAll(0);

        assertEquals(1, result.createdCount());
        verify(facilitySyncService).syncAll(any());
    }

    private PoliceStationApiResponse response(int pageNo, int totalCount, List<PoliceStationApiResponse.Item> items) {
        return new PoliceStationApiResponse(
                new PoliceStationApiResponse.Header("00", "OK"),
                new PoliceStationApiResponse.Body(new PoliceStationApiResponse.Items(items), 100, pageNo, totalCount));
    }

    private PoliceStationApiResponse.Item item(String objectId, String latitude, String longitude) {
        return new PoliceStationApiResponse.Item(
                objectId,
                "112",
                "Police Station",
                "Lot address",
                "Road address",
                toWebMercatorX(longitude),
                toWebMercatorY(latitude));
    }

    private String toWebMercatorX(String longitude) {
        return longitude.isBlank() ? "" : Double.toString(6_378_137.0 * Math.toRadians(Double.parseDouble(longitude)));
    }

    private String toWebMercatorY(String latitude) {
        if (latitude.isBlank()) {
            return "";
        }
        double latitudeRadians = Math.toRadians(Double.parseDouble(latitude));
        return Double.toString(6_378_137.0 * Math.log(Math.tan(Math.PI / 4 + latitudeRadians / 2)));
    }
}
