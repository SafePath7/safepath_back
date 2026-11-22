package com.safepath.backend.risk.service;

import java.util.List;
import java.util.UUID;

import com.safepath.backend.common.auth.CurrentUser.Actor;
import com.safepath.backend.common.exception.ApiException;
import com.safepath.backend.common.util.UtcTime;
import com.safepath.backend.risk.dto.EventRequest;
import com.safepath.backend.risk.dto.RiskEventDetailResponse;
import com.safepath.backend.risk.dto.RiskEventResponse;
import com.safepath.backend.risk.entity.RiskEvent;
import com.safepath.backend.risk.entity.RiskSessionSnapshot;
import com.safepath.backend.risk.notification.RiskAlertPolicy;
import com.safepath.backend.risk.repository.RiskEventRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskService {
    private final RiskEventRepository events;
    private final RiskAlertPolicy alerts;
    private final RiskSessionBridge sessions;
    private final RiskEventAccess access;

    public RiskService(RiskEventRepository events, RiskAlertPolicy alerts,
            RiskSessionBridge sessions, RiskEventAccess access) {
        this.events = events;
        this.alerts = alerts;
        this.sessions = sessions;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public RiskEventDetailResponse getEvent(Actor actor, long eventId) {
        RiskEvent event = events.findWithSessionById(eventId)
                .orElseThrow(() -> ApiException.notFound("위험 이벤트"));
        access.requireViewer(actor, event.getUserId());
        return RiskEventDetailResponse.from(event);
    }

    /** A null userId means the caller's own events; a null sessionId means every session. */
    @Transactional(readOnly = true)
    public List<RiskEventDetailResponse> getEvents(Actor actor, Long userId, UUID sessionId, int limit) {
        long target = userId == null ? actor.id() : userId;
        access.requireViewer(actor, target);
        var page = PageRequest.of(0, limit);
        List<RiskEvent> found = sessionId == null
                ? events.findByUserId(target, page)
                : events.findByUserIdAndSessionId(target, sessionId, page);
        return found.stream().map(RiskEventDetailResponse::from).toList();
    }

    @Transactional
    public RiskEventResponse createEvent(Actor actor, EventRequest request) {
        RiskSessionSnapshot session = sessions.ensurePersistedOwner(actor, request.sessionId());
        RiskEvent event = events.saveAndFlush(new RiskEvent(session, request.eventType(),
                request.description(), UtcTime.fromInstant(request.detectedAt())));
        alerts.onCreated(event);
        return RiskEventResponse.from(event);
    }

    @Transactional
    public RiskEventResponse updateEvent(Actor actor, long eventId, String status) {
        // The row lock keeps concurrent status changes from interleaving.
        RiskEvent event = events.findForUpdateById(eventId)
                .orElseThrow(() -> ApiException.notFound("위험 이벤트"));
        long userId = event.getUserId();
        // Ownership first: the owner's request needs no Firebase admin lookup.
        if (actor.id() != userId) actor.requireAdmin();
        String previousStatus = event.getStatus();
        event.changeStatus(status);
        events.saveAndFlush(event);
        alerts.onStatusChanged(event, previousStatus);
        return RiskEventResponse.from(event);
    }
}
