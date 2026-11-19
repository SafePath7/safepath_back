package com.safepath.backend.safety.facility.emergencycallbox;

import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "safepath.emergency-call-box.sync.enabled", havingValue = "true")
public class EmergencyCallBoxSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(EmergencyCallBoxSyncScheduler.class);
    private static final String SOURCE = "EMERGENCY_CALL_BOX";

    private final EmergencyCallBoxSyncService syncService;
    private final SafetyFacilitySyncService facilitySyncService;
    private final int startPage;
    private final int maxPages;
    private boolean initialSync = true;

    public EmergencyCallBoxSyncScheduler(
            EmergencyCallBoxSyncService syncService,
            SafetyFacilitySyncService facilitySyncService,
            @Value("${safepath.emergency-call-box.sync.start-page:1}") int startPage,
            @Value("${safepath.emergency-call-box.sync.max-pages:0}") int maxPages) {
        this.syncService = syncService;
        this.facilitySyncService = facilitySyncService;
        this.startPage = startPage;
        this.maxPages = maxPages;
    }

    @Scheduled(
            initialDelayString = "${safepath.emergency-call-box.sync.initial-delay-ms:0}",
            fixedDelayString = "${safepath.emergency-call-box.sync.interval-ms:7776000000}")
    public void sync() {
        if (isInitialSyncAndFacilitiesExist()) {
            log.info("Emergency call box initial sync skipped because facilities already exist");
            return;
        }

        log.info("Emergency call box sync started: startPage={}, maxPages={}", startPage, maxPages);
        EmergencyCallBoxSyncService.SyncResult result = syncService.syncAll(startPage, maxPages);
        log.info("Emergency call box sync completed: pages={}, total={}, created={}, updated={}",
                result.pageCount(), result.totalCount(), result.createdCount(), result.updatedCount());
    }

    private boolean isInitialSyncAndFacilitiesExist() {
        if (!initialSync) {
            return false;
        }
        initialSync = false;
        return startPage == 1 && facilitySyncService.hasFacilities(SOURCE);
    }
}
