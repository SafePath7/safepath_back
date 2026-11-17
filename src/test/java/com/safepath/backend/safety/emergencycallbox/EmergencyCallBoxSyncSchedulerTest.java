package com.safepath.backend.safety.emergencycallbox;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class EmergencyCallBoxSyncSchedulerTest {
    @Test
    void syncsConfiguredMaximumPageCount() {
        EmergencyCallBoxSyncService syncService = mock(EmergencyCallBoxSyncService.class);
        when(syncService.syncAll(100)).thenReturn(new EmergencyCallBoxSyncService.SyncResult(100, 88_634, 1, 0));
        EmergencyCallBoxSyncScheduler scheduler = new EmergencyCallBoxSyncScheduler(syncService, 100);

        scheduler.sync();

        verify(syncService).syncAll(100);
    }
}
