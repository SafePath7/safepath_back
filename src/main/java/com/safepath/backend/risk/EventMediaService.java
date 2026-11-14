package com.safepath.backend.risk;

import java.util.List;

import com.safepath.backend.guardianrisk.ApiException;
import com.safepath.backend.risk.RiskModels.Media;
import com.safepath.backend.guardianrisk.CurrentUser.Actor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class EventMediaService {
    private static final Logger log = LoggerFactory.getLogger(EventMediaService.class);
    private final RiskRepository repository;
    private final EventMediaStorage storage;

    public EventMediaService(RiskRepository repository, EventMediaStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    @Transactional
    public Media uploadEventMedia(Actor actor, long eventId, MultipartFile file, String mediaType) {
        actor.requireAdmin();
        requireEvent(eventId, true);
        var stored = storage.store(file);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) cleanup(stored.key(), eventId);
            }
        });
        return repository.insertMedia(eventId, mediaType, stored.fileName(), stored.key());
    }

    @Transactional(readOnly = true)
    public List<Media> getEventMedias(Actor actor, long eventId) {
        actor.requireAdmin();
        requireEvent(eventId, false);
        return repository.medias(eventId);
    }

    @Transactional(readOnly = true)
    public MediaDownload downloadEventMedia(Actor actor, long eventId, long mediaId) {
        actor.requireAdmin();
        requireEvent(eventId, false);
        Media media = repository.media(eventId, mediaId, false)
                .orElseThrow(() -> ApiException.notFound("이벤트 미디어"));
        if (media.storageUrl() == null) throw ApiException.notFound("미디어 파일");
        return new MediaDownload(media.fileName(), storage.read(media.storageUrl()));
    }

    public record MediaDownload(String fileName, byte[] contents) {}

    @Transactional
    public void deleteEventMedia(Actor actor, long eventId, long mediaId) {
        actor.requireAdmin();
        requireEvent(eventId, true);
        Media media = repository.media(eventId, mediaId).orElseThrow(() -> ApiException.notFound("이벤트 미디어"));
        if (media.storageUrl() != null) {
            // A storage failure must reach the caller before the metadata is removed.
            var deletion = storage.deleteWithRollback(media.storageUrl());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public int getOrder() {
                    // Restore first if this same transaction also registered upload rollback cleanup.
                    return Ordered.LOWEST_PRECEDENCE - 1;
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_ROLLED_BACK) return;
                    try {
                        deletion.restore();
                    } catch (RuntimeException exception) {
                        log.error("Media file restore needs retry: eventId={}, mediaId={}, storageKey={}",
                                eventId, mediaId, media.storageUrl());
                    }
                }
            });
        }
        repository.deleteMedia(eventId, mediaId);
    }

    private void requireEvent(long eventId, boolean lock) {
        repository.event(eventId, lock).orElseThrow(() -> ApiException.notFound("위험 이벤트"));
    }

    private void cleanup(String key, long eventId) {
        // storage_url is nullable in the supplied schema; no file exists to delete in that case.
        if (key == null) return;
        try {
            storage.delete(key);
        } catch (RuntimeException exception) {
            log.error("Media cleanup needs retry: eventId={}, storageKey={}", eventId, key);
        }
    }
}
