package com.safepath.backend.risk.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.safepath.backend.common.auth.CurrentUser.Actor;
import com.safepath.backend.common.exception.ApiException;
import com.safepath.backend.common.util.UtcTime;
import com.safepath.backend.risk.entity.RiskSessionSnapshot;
import com.safepath.backend.risk.repository.RiskSessionSnapshotRepository;
import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.store.ActiveSessionStore;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the active session with its first risk event and updates the existing
 * snapshot when the session ends.
 */
@Component
public class RiskSessionBridge {
    private final RiskSessionSnapshotRepository snapshots;
    private final ActiveSessionStore activeSessions;

    public RiskSessionBridge(RiskSessionSnapshotRepository snapshots, ActiveSessionStore activeSessions) {
        this.snapshots = snapshots;
        this.activeSessions = activeSessions;
    }

    public Optional<ActiveSession> findActiveSession(UUID sessionId) {
        return activeSessions.findById(sessionId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void endSnapshot(UUID sessionId, long userId, Instant endedAt) {
        snapshots.markEnded(sessionId, userId, RiskSessionSnapshot.ENDED, UtcTime.fromInstant(endedAt));
    }

    /** Returns the saved session, which the caller must own. */
    @Transactional(propagation = Propagation.MANDATORY)
    public RiskSessionSnapshot ensurePersistedOwner(Actor actor, UUID sessionId) {
        var persisted = snapshots.findById(sessionId);
        if (persisted.isPresent()) {
            actor.requireOwner(persisted.get().getUserId());
            return persisted.get();
        }

        ActiveSession session = activeSessions.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("안전귀가 세션"));
        actor.requireOwner(session.userId());
        // Sharing the event transaction prevents an orphan snapshot after a failed write.
        snapshots.insertIfAbsent(session.id(), session.userId(),
                UtcTime.fromInstant(session.startedAt()), UtcTime.now());
        // Re-read: a concurrent request may have inserted this id for its own session.
        RiskSessionSnapshot saved = snapshots.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("안전귀가 세션"));
        actor.requireOwner(saved.getUserId());
        return saved;
    }
}
