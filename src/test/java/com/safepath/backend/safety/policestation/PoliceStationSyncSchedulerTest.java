package com.safepath.backend.safety.policestation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class PoliceStationSyncSchedulerTest {
    @Test
    void syncsAllPages() {
        PoliceStationSyncService syncService = mock(PoliceStationSyncService.class);
        when(syncService.syncAll(100)).thenReturn(new PoliceStationSyncService.SyncResult(100, 2_000, 1, 0));
        PoliceStationSyncScheduler scheduler = new PoliceStationSyncScheduler(syncService, 100);

        scheduler.sync();

        verify(syncService).syncAll(100);
    }
}
