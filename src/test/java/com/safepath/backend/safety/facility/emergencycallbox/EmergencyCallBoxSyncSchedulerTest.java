package com.safepath.backend.safety.facility.emergencycallbox;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.junit.jupiter.api.Test;

class EmergencyCallBoxSyncSchedulerTest {
    @Test
    void syncsConfiguredMaximumPageCount() {
        EmergencyCallBoxSyncService syncService = mock(EmergencyCallBoxSyncService.class);
        SafetyFacilitySyncService facilitySyncService = mock(SafetyFacilitySyncService.class);
        when(syncService.syncAll(1, 100)).thenReturn(new EmergencyCallBoxSyncService.SyncResult(100, 88_634, 1, 0));
        EmergencyCallBoxSyncScheduler scheduler = new EmergencyCallBoxSyncScheduler(syncService, facilitySyncService, 1, 100);

        scheduler.sync();

        verify(syncService).syncAll(1, 100);
    }
}
