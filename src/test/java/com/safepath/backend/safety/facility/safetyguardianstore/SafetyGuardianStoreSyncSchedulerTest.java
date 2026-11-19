package com.safepath.backend.safety.facility.safetyguardianstore;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.junit.jupiter.api.Test;

class SafetyGuardianStoreSyncSchedulerTest {
    @Test
    void syncsWhenNoFacilitiesExistForSource() {
        SafetyGuardianStoreSyncService syncService = mock(SafetyGuardianStoreSyncService.class);
        SafetyFacilitySyncService facilitySyncService = mock(SafetyFacilitySyncService.class);
        when(syncService.syncAll(0)).thenReturn(new SafetyGuardianStoreSyncService.SyncResult(1, 1, 1, 0));
        SafetyGuardianStoreSyncScheduler scheduler = new SafetyGuardianStoreSyncScheduler(
                syncService, facilitySyncService, 0);

        scheduler.sync();

        verify(syncService).syncAll(0);
    }
}
