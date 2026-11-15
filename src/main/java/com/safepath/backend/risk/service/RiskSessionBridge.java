package com.safepath.backend.risk.service;

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
 * Persists the existing session module's in-memory session only when its first risk
 * event is saved. ACTIVE is a snapshot at this point, not a synchronized session
 * lifecycle. This module never changes the teammate's in-memory session store.
 * The session module's status rules are still undecided; endSession does not update
 * this snapshot's status or ended_at. Those fields require an agreed lifecycle.
 */
@Component
public class RiskSessionBridge {
    private final RiskSessionSnapshotRepository snapshots;
    private final ActiveSessionStore activeSessions;

    public RiskSessionBridge(RiskSessionSnapshotRepository snapshots, ActiveSessionStore activeSessions) {
        this.snapshots = snapshots;
        this.activeSessions = activeSessions;
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
