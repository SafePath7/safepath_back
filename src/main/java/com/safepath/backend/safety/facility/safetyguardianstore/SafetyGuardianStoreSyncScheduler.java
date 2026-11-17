package com.safepath.backend.safety.facility.safetyguardianstore;

import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "safepath.safety-guardian-store.sync.enabled", havingValue = "true")
public class SafetyGuardianStoreSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(SafetyGuardianStoreSyncScheduler.class);
    private static final String SOURCE = "SAFETY_GUARDIAN_STORE";

    private final SafetyGuardianStoreSyncService syncService;
    private final SafetyFacilitySyncService facilitySyncService;
    private final int maxPages;
    private boolean initialSync = true;

    public SafetyGuardianStoreSyncScheduler(
            SafetyGuardianStoreSyncService syncService,
            SafetyFacilitySyncService facilitySyncService,
            @Value("${safepath.safety-guardian-store.sync.max-pages:0}") int maxPages) {
        this.syncService = syncService;
        this.facilitySyncService = facilitySyncService;
        this.maxPages = maxPages;
    }

    @Scheduled(
            initialDelayString = "${safepath.safety-guardian-store.sync.initial-delay-ms:0}",
            fixedDelayString = "${safepath.safety-guardian-store.sync.interval-ms:7776000000}")
    public void sync() {
        if (isInitialSyncAndFacilitiesExist()) {
            log.info("Safety guardian store initial sync skipped because facilities already exist");
            return;
        }

        SafetyGuardianStoreSyncService.SyncResult result = syncService.syncAll(maxPages);
        log.info("Safety guardian store sync completed: pages={}, total={}, created={}, updated={}",
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
