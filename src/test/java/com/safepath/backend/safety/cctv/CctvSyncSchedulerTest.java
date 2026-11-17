package com.safepath.backend.safety.cctv;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class CctvSyncSchedulerTest {
    @Test
    void syncsAllPages() {
        CctvSyncService syncService = mock(CctvSyncService.class);
        when(syncService.syncAll(100)).thenReturn(new CctvSyncService.SyncResult(100, 381_469, 1, 0));
        CctvSyncScheduler scheduler = new CctvSyncScheduler(syncService, 100);

        scheduler.sync();

        verify(syncService).syncAll(100);
    }
}
