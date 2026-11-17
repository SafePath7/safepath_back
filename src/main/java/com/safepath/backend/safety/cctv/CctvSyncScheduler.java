package com.safepath.backend.safety.cctv;

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

    private final CctvSyncService syncService;
    private final int maxPages;

    public CctvSyncScheduler(
            CctvSyncService syncService,
            @Value("${safepath.cctv.sync.max-pages:0}") int maxPages) {
        this.syncService = syncService;
        this.maxPages = maxPages;
    }

    @Scheduled(
            initialDelayString = "${safepath.cctv.sync.initial-delay-ms:0}",
            fixedDelayString = "${safepath.cctv.sync.interval-ms:7776000000}")
    public void sync() {
        CctvSyncService.SyncResult result = syncService.syncAll(maxPages);
        log.info("CCTV sync completed: pages={}, total={}, created={}, updated={}",
                result.pageCount(), result.totalCount(), result.createdCount(), result.updatedCount());
    }
}
