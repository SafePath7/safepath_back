package com.safepath.backend.safety.facility.cctv;

import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "safepath.cctv.sync.enabled", havingValue = "true")
public class CctvSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(CctvSyncScheduler.class);
    private static final String SOURCE = "CCTV";

    private final CctvSyncService syncService;
    private final SafetyFacilitySyncService facilitySyncService;
    private final int maxPages;
    private boolean initialSync = true;

    public CctvSyncScheduler(
            CctvSyncService syncService,
            SafetyFacilitySyncService facilitySyncService,
            @Value("${safepath.cctv.sync.max-pages:0}") int maxPages) {
        this.syncService = syncService;
        this.facilitySyncService = facilitySyncService;
        this.maxPages = maxPages;
    }

    @Scheduled(
            initialDelayString = "${safepath.cctv.sync.initial-delay-ms:0}",
            fixedDelayString = "${safepath.cctv.sync.interval-ms:7776000000}")
    public void sync() {
        if (isInitialSyncAndFacilitiesExist()) {
            log.info("CCTV initial sync skipped because facilities already exist");
            return;
        }

        CctvSyncService.SyncResult result = syncService.syncAll(maxPages);
        log.info("CCTV sync completed: pages={}, total={}, created={}, updated={}",
                result.pageCount(), result.totalCount(), result.createdCount(), result.updatedCount());
    }

    private boolean isInitialSyncAndFacilitiesExist() {
        if (!initialSync) {
            return false;
        }
        initialSync = false;
        return facilitySyncService.hasFacilities(SOURCE);
    }
}
