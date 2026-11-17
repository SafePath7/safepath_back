package com.safepath.backend.safety.policestation;

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

    private final PoliceStationSyncService syncService;
    private final int maxPages;

    public PoliceStationSyncScheduler(
            PoliceStationSyncService syncService,
            @Value("${safepath.police-station.sync.max-pages:0}") int maxPages) {
        this.syncService = syncService;
        this.maxPages = maxPages;
    }

    @Scheduled(
            initialDelayString = "${safepath.police-station.sync.initial-delay-ms:0}",
            fixedDelayString = "${safepath.police-station.sync.interval-ms:7776000000}")
    public void sync() {
        PoliceStationSyncService.SyncResult result = syncService.syncAll(maxPages);
        log.info("Police station sync completed: pages={}, total={}, created={}, updated={}",
                result.pageCount(), result.totalCount(), result.createdCount(), result.updatedCount());
    }
}
