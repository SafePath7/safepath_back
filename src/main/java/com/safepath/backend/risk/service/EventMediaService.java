package com.safepath.backend.risk.service;

import java.util.List;

import com.safepath.backend.common.auth.CurrentUser.Actor;
import com.safepath.backend.common.exception.ApiException;
import com.safepath.backend.risk.dto.MediaResponse;
import com.safepath.backend.risk.entity.RiskEvent;
import com.safepath.backend.risk.entity.RiskEventMedia;
import com.safepath.backend.risk.repository.RiskEventMediaRepository;
import com.safepath.backend.risk.repository.RiskEventRepository;
import com.safepath.backend.risk.storage.EventMediaStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/** Reading follows the event's own rule; upload and delete are admin-only. */
@Service
public class EventMediaService {
    private static final Logger log = LoggerFactory.getLogger(EventMediaService.class);
    private final RiskEventRepository events;
    private final RiskEventMediaRepository medias;
    private final EventMediaStorage storage;
    private final RiskEventAccess access;

    public EventMediaService(RiskEventRepository events, RiskEventMediaRepository medias,
            EventMediaStorage storage, RiskEventAccess access) {
        this.events = events;
        this.medias = medias;
        this.storage = storage;
        this.access = access;
    }

    @Transactional
    public MediaResponse uploadEventMedia(Actor actor, long eventId, MultipartFile file, String mediaType) {
        actor.requireAdmin();
        lockEvent(eventId);
        var stored = storage.store(file);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) cleanup(stored.key(), eventId);
            }
        });
        return MediaResponse.from(medias.saveAndFlush(
                new RiskEventMedia(eventId, mediaType, stored.fileName(), stored.key())));
    }

    @Transactional(readOnly = true)
    public List<MediaResponse> getEventMedias(Actor actor, long eventId) {
        requireViewer(actor, eventId);
        return medias.findByEventIdOrderById(eventId).stream().map(MediaResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public MediaDownload downloadEventMedia(Actor actor, long eventId, long mediaId) {
        requireViewer(actor, eventId);
        RiskEventMedia media = medias.findByEventIdAndId(eventId, mediaId)
                .orElseThrow(() -> ApiException.notFound("이벤트 미디어"));
        // storage_url is nullable in the V1 schema; such a row has no file to return.
        if (media.getStorageUrl() == null) throw ApiException.notFound("미디어 파일");
        return new MediaDownload(media.getFileName(), storage.read(media.getStorageUrl()));
    }

    public record MediaDownload(String fileName, byte[] contents) {}

    @Transactional
    public void deleteEventMedia(Actor actor, long eventId, long mediaId) {
        actor.requireAdmin();
        lockEvent(eventId);
        RiskEventMedia media = medias.findForUpdateByEventIdAndId(eventId, mediaId)
                .orElseThrow(() -> ApiException.notFound("이벤트 미디어"));
        String storageUrl = media.getStorageUrl();
        if (storageUrl != null) {
            // A storage failure must reach the caller before the metadata is removed.
            var deletion = storage.deleteWithRollback(storageUrl);
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
                                eventId, mediaId, storageUrl);
                    }
                }
            });
        }
        medias.delete(media);
        medias.flush();
    }

    private void requireViewer(Actor actor, long eventId) {
        RiskEvent event = events.findWithSessionById(eventId)
                .orElseThrow(() -> ApiException.notFound("위험 이벤트"));
        access.requireViewer(actor, event.getUserId());
    }

    /** Uploads and deletes for one event run one at a time. */
    private void lockEvent(long eventId) {
        events.findForUpdateById(eventId).orElseThrow(() -> ApiException.notFound("위험 이벤트"));
    }

    private void cleanup(String key, long eventId) {
        if (key == null) return;
        try {
            storage.delete(key);
        } catch (RuntimeException exception) {
            log.error("Media cleanup needs retry: eventId={}, storageKey={}", eventId, key);
        }
    }
}
