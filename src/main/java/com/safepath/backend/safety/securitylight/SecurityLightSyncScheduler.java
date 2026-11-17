package com.safepath.backend.safety.securitylight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "safepath.security-light.sync.enabled", havingValue = "true")
public class SecurityLightSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(SecurityLightSyncScheduler.class);

    private final SecurityLightSyncService syncService;

    public SecurityLightSyncScheduler(SecurityLightSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(
            initialDelayString = "${safepath.security-light.sync.initial-delay-ms:0}",
            fixedDelayString = "${safepath.security-light.sync.interval-ms:7776000000}")
    public void sync() {
        SecurityLightSyncService.SyncResult result = syncService.syncAll();
        log.info("Security light sync completed: pages={}, total={}, created={}, updated={}",
                result.pageCount(), result.totalCount(), result.createdCount(), result.updatedCount());
    }
}
