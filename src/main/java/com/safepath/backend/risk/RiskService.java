package com.safepath.backend.risk;

import java.util.List;
import java.util.UUID;

import com.safepath.backend.guardianrisk.ApiException;
import com.safepath.backend.risk.RiskModels.*;
import com.safepath.backend.guardianrisk.CurrentUser.Actor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskService {
    private final RiskRepository repository;
    private final RiskEventNotifications notifications;
    private final RiskSessionBridge sessions;
    private final RiskEventAccess access;

    public RiskService(RiskRepository repository, RiskEventNotifications notifications, RiskSessionBridge sessions,
            RiskEventAccess access) {
        this.repository = repository;
        this.notifications = notifications;
        this.sessions = sessions;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public RiskEventDetail getEvent(Actor actor, long eventId) {
        RiskEventDetail event = repository.eventDetail(eventId)
                .orElseThrow(() -> ApiException.notFound("위험 이벤트"));
        access.requireViewer(actor, event.userId());
        return event;
    }

    /** A null userId means the caller's own events. */
    @Transactional(readOnly = true)
    public List<RiskEventDetail> getEvents(Actor actor, Long userId, UUID sessionId, int limit) {
        long target = userId == null ? actor.id() : userId;
        access.requireViewer(actor, target);
        return repository.eventsOf(target, sessionId, limit);
    }

    @Transactional
    public RiskEvent createEvent(Actor actor, EventRequest request) {
        long userId = sessions.ensurePersistedOwner(actor, request.sessionId());
        RiskEvent event = repository.insertEvent(request);
        notifications.afterCommit(userId, event);
        return event;
    }

    @Transactional
    public RiskEvent updateEvent(Actor actor, long eventId, String status) {
        var event = repository.event(eventId, true).orElseThrow(() -> ApiException.notFound("위험 이벤트"));
        long userId = owner(event.sessionId());
        // Ownership first: the owner's request needs no Firebase admin lookup.
        if (actor.id() != userId) actor.requireAdmin();
        RiskEvent updated = repository.updateStatus(eventId, status);
        notifications.afterCommit(userId, updated);
        return updated;
    }

    private long owner(UUID sessionId) {
        return repository.sessionOwner(sessionId).orElseThrow(() -> ApiException.notFound("안전귀가 세션"));
    }
}
