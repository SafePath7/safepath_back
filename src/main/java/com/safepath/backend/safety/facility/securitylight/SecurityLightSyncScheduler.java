package com.safepath.backend.safety.facility.securitylight;

import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "safepath.security-light.sync.enabled", havingValue = "true")
public class SecurityLightSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(SecurityLightSyncScheduler.class);
    private static final String SOURCE = "SECURITY_LIGHT";

    private final SecurityLightSyncService syncService;
    private final SafetyFacilitySyncService facilitySyncService;
    private boolean initialSync = true;

    public SecurityLightSyncScheduler(
            SecurityLightSyncService syncService,
            SafetyFacilitySyncService facilitySyncService) {
        this.syncService = syncService;
        this.facilitySyncService = facilitySyncService;
    }

    @Scheduled(
            initialDelayString = "${safepath.security-light.sync.initial-delay-ms:0}",
            fixedDelayString = "${safepath.security-light.sync.interval-ms:7776000000}")
    public void sync() {
        if (isInitialSyncAndFacilitiesExist()) {
            log.info("Security light initial sync skipped because facilities already exist");
            return;
        }

        SecurityLightSyncService.SyncResult result = syncService.syncAll();
        log.info("Security light sync completed: pages={}, total={}, created={}, updated={}",
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
