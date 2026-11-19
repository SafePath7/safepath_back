package com.safepath.backend.safety.facility.policestation;

import com.safepath.backend.safety.service.SafetyFacilitySyncService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "safepath.police-station.sync.enabled", havingValue = "true")
public class PoliceStationSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(PoliceStationSyncScheduler.class);
    private static final String SOURCE = "POLICE_STATION";

    private final PoliceStationSyncService syncService;
    private final SafetyFacilitySyncService facilitySyncService;
    private final int maxPages;
    private boolean initialSync = true;

    public PoliceStationSyncScheduler(
            PoliceStationSyncService syncService,
            SafetyFacilitySyncService facilitySyncService,
            @Value("${safepath.police-station.sync.max-pages:0}") int maxPages) {
        this.syncService = syncService;
        this.facilitySyncService = facilitySyncService;
        this.maxPages = maxPages;
    }

    @Scheduled(
            initialDelayString = "${safepath.police-station.sync.initial-delay-ms:0}",
            fixedDelayString = "${safepath.police-station.sync.interval-ms:7776000000}")
    public void sync() {
        if (isInitialSyncAndFacilitiesExist()) {
            log.info("Police station initial sync skipped because facilities already exist");
            return;
        }

        PoliceStationSyncService.SyncResult result = syncService.syncAll(maxPages);
        log.info("Police station sync completed: pages={}, total={}, created={}, updated={}",
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
