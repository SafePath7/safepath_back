package com.safepath.backend.safety.facility.policestation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.junit.jupiter.api.Test;

class PoliceStationSyncSchedulerTest {
    @Test
    void syncsAllPages() {
        PoliceStationSyncService syncService = mock(PoliceStationSyncService.class);
        SafetyFacilitySyncService facilitySyncService = mock(SafetyFacilitySyncService.class);
        when(syncService.syncAll(100)).thenReturn(new PoliceStationSyncService.SyncResult(100, 2_000, 1, 0));
        PoliceStationSyncScheduler scheduler = new PoliceStationSyncScheduler(syncService, facilitySyncService, 100);

        scheduler.sync();

        verify(syncService).syncAll(100);
    }
}
