package com.safepath.backend.risk;

import java.util.UUID;

import com.safepath.backend.guardianrisk.ApiException;
import com.safepath.backend.guardianrisk.CurrentUser.Actor;
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
    private final RiskRepository repository;
    private final ActiveSessionStore activeSessions;

    public RiskSessionBridge(RiskRepository repository, ActiveSessionStore activeSessions) {
        this.repository = repository;
        this.activeSessions = activeSessions;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long ensurePersistedOwner(Actor actor, UUID sessionId) {
        var persistedOwner = repository.sessionOwner(sessionId);
        if (persistedOwner.isPresent()) {
            actor.requireOwner(persistedOwner.get());
            return persistedOwner.get();
        }

        ActiveSession session = activeSessions.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("안전귀가 세션"));
        actor.requireOwner(session.userId());
        // Sharing the event transaction prevents an orphan snapshot after a failed write.
        // ON CONFLICT never replaces a row inserted by another request.
        repository.insertSessionSnapshot(session.id(), session.userId(), session.startedAt());
        long savedOwner = repository.sessionOwner(sessionId)
                .orElseThrow(() -> ApiException.notFound("안전귀가 세션"));
        actor.requireOwner(savedOwner);
        return savedOwner;
    }
}
