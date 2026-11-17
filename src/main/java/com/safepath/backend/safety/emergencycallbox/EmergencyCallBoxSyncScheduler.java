package com.safepath.backend.safety.emergencycallbox;

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

    private final EmergencyCallBoxSyncService syncService;
    private final int maxPages;

    public EmergencyCallBoxSyncScheduler(
            EmergencyCallBoxSyncService syncService,
            @Value("${safepath.emergency-call-box.sync.max-pages:0}") int maxPages) {
        this.syncService = syncService;
        this.maxPages = maxPages;
    }

    @Scheduled(
            initialDelayString = "${safepath.emergency-call-box.sync.initial-delay-ms:0}",
            fixedDelayString = "${safepath.emergency-call-box.sync.interval-ms:7776000000}")
    public void sync() {
        EmergencyCallBoxSyncService.SyncResult result = syncService.syncAll(maxPages);
        log.info("Emergency call box sync completed: pages={}, total={}, created={}, updated={}",
                result.pageCount(), result.totalCount(), result.createdCount(), result.updatedCount());
    }
}
