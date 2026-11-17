package com.safepath.backend.safety.facility.cctv;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.junit.jupiter.api.Test;

class CctvSyncSchedulerTest {
    @Test
    void syncsAllPages() {
        CctvSyncService syncService = mock(CctvSyncService.class);
        SafetyFacilitySyncService facilitySyncService = mock(SafetyFacilitySyncService.class);
        when(syncService.syncAll(100)).thenReturn(new CctvSyncService.SyncResult(100, 381_469, 1, 0));
        CctvSyncScheduler scheduler = new CctvSyncScheduler(syncService, facilitySyncService, 100);

        scheduler.sync();

        verify(syncService).syncAll(100);
    }

    @Test
    void skipsInitialSyncWhenFacilitiesAlreadyExist() {
        CctvSyncService syncService = mock(CctvSyncService.class);
        SafetyFacilitySyncService facilitySyncService = mock(SafetyFacilitySyncService.class);
        when(facilitySyncService.hasFacilities("CCTV")).thenReturn(true);
        when(syncService.syncAll(0)).thenReturn(new CctvSyncService.SyncResult(1, 1, 0, 1));
        CctvSyncScheduler scheduler = new CctvSyncScheduler(syncService, facilitySyncService, 0);

        scheduler.sync();

        verifyNoInteractions(syncService);

        scheduler.sync();

        verify(syncService).syncAll(0);
    }
}
