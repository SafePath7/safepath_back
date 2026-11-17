package com.safepath.backend.safety.securitylight;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class SecurityLightSyncSchedulerTest {
    @Test
    void syncsAllPages() {
        SecurityLightSyncService syncService = mock(SecurityLightSyncService.class);
        when(syncService.syncAll())
                .thenReturn(new SecurityLightSyncService.SyncResult(1_862, 1_861_604, 1, 0));
        SecurityLightSyncScheduler scheduler = new SecurityLightSyncScheduler(syncService);

        scheduler.sync();

        verify(syncService).syncAll();
    }
}
