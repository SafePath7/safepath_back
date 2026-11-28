package com.safepath.backend.risk.service;

import java.time.LocalDateTime;
import java.util.List;

import com.safepath.backend.common.util.UtcTime;
import com.safepath.backend.risk.entity.RiskEventMedia;
import com.safepath.backend.risk.repository.RiskEventMediaRepository;
import com.safepath.backend.risk.storage.EventMediaStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event media files are kept for a fixed number of days. After that the file is deleted and
 * the media row's storage_url becomes null; the event and media rows themselves stay.
 * The S3 bucket's lifecycle rule on risk-events/ expires the same objects as a backstop, so
 * retention-days must match that rule.
 */
@Service
public class EventMediaRetentionService {
    private static final Logger log = LoggerFactory.getLogger(EventMediaRetentionService.class);
    static final int BATCH_SIZE = 100;

    private final RiskEventMediaRepository medias;
    private final EventMediaStorage storage;
    private final int retentionDays;

    public EventMediaRetentionService(RiskEventMediaRepository medias, EventMediaStorage storage,
            @Value("${safepath.media.retention-days:7}") int retentionDays) {
        if (retentionDays < 1) throw new IllegalArgumentException("safepath.media.retention-days must be at least 1");
        this.medias = medias;
        this.storage = storage;
        this.retentionDays = retentionDays;
    }

    /** Expires one batch and reports how many rows were read and how many were cleared. */
    @Transactional
    public BatchResult expireBatch() {
        LocalDateTime now = UtcTime.now();
        List<RiskEventMedia> expired = medias.findExpiredForUpdate(
                now.minusDays(retentionDays), PageRequest.of(0, BATCH_SIZE));
        int cleared = 0;
        for (RiskEventMedia media : expired) {
            try {
                // Deleting an object the lifecycle rule already removed succeeds as a no-op.
                storage.delete(media.getStorageUrl());
            } catch (RuntimeException exception) {
                // Keep the location so the next run retries the delete.
                log.warn("Expired media file delete failed: mediaId={}, eventId={}",
                        media.getId(), media.getEventId());
                continue;
            }
            media.clearStorage(now);
            cleared++;
        }
        return new BatchResult(expired.size(), cleared);
    }

    public record BatchResult(int found, int cleared) {}
}
