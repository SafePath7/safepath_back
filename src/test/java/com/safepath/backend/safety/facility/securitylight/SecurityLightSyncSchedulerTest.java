package com.safepath.backend.safety.facility.securitylight;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.junit.jupiter.api.Test;

class SecurityLightSyncSchedulerTest {
    @Test
    void syncsAllPages() {
        SecurityLightSyncService syncService = mock(SecurityLightSyncService.class);
        SafetyFacilitySyncService facilitySyncService = mock(SafetyFacilitySyncService.class);
        when(syncService.syncAll())
                .thenReturn(new SecurityLightSyncService.SyncResult(1_862, 1_861_604, 1, 0));
        SecurityLightSyncScheduler scheduler = new SecurityLightSyncScheduler(syncService, facilitySyncService);

        scheduler.sync();

        verify(syncService).syncAll();
    }
}
