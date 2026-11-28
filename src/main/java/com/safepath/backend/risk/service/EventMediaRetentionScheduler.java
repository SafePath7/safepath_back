package com.safepath.backend.risk.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "safepath.media.retention.enabled", havingValue = "true", matchIfMissing = true)
public class EventMediaRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(EventMediaRetentionScheduler.class);

    private final EventMediaRetentionService retention;

    public EventMediaRetentionScheduler(EventMediaRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(
            initialDelayString = "${safepath.media.retention.initial-delay-ms:60000}",
            fixedDelayString = "${safepath.media.retention.interval-ms:3600000}")
    public void expire() {
        int cleared = 0;
        EventMediaRetentionService.BatchResult batch;
        do {
            batch = retention.expireBatch();
            cleared += batch.cleared();
            // Stop on a short batch, or when a full batch is all failed deletes (they would repeat).
        } while (batch.found() == EventMediaRetentionService.BATCH_SIZE && batch.cleared() > 0);
        if (cleared > 0) log.info("Expired event media files: cleared={}", cleared);
    }
}
