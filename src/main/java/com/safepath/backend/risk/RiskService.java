package com.safepath.backend.risk;

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

    public RiskService(RiskRepository repository, RiskEventNotifications notifications, RiskSessionBridge sessions) {
        this.repository = repository;
        this.notifications = notifications;
        this.sessions = sessions;
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
