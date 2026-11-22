package com.safepath.backend.risk.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RiskService {
    private final RiskEventRepository events;
    private final RiskAlertPolicy alerts;
    private final RiskSessionBridge sessions;
    private final RiskEventAccess access;
    private final TransactionTemplate creationTransaction;

    public RiskService(RiskEventRepository events, RiskAlertPolicy alerts,
            RiskSessionBridge sessions, RiskEventAccess access, PlatformTransactionManager transactionManager) {
        this.events = events;
        this.alerts = alerts;
        this.sessions = sessions;
        this.access = access;
        this.creationTransaction = new TransactionTemplate(transactionManager);
        this.creationTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.creationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

    public record EventCreation(RiskEventResponse response, boolean created) {}

    public RiskEventResponse createEvent(Actor actor, EventRequest request) {
        return createOrReuseEvent(actor, request).response();
    }

    public EventCreation createOrReuseEvent(Actor actor, EventRequest request) {
        var active = sessions.findActiveSession(request.sessionId());
        if (active.isPresent()) {
            // Hold the same session monitor as endSession through the actual database commit.
            synchronized (active.get()) {
                return creationTransaction.execute(ignored -> persistEvent(actor, request));
            }
        }
        return creationTransaction.execute(ignored -> persistEvent(actor, request));
    }

    private EventCreation persistEvent(Actor actor, EventRequest request) {
        LocalDateTime detectedAt = UtcTime.fromInstant(request.detectedAt());
        if (request.eventKey() == null) {
            RiskSessionSnapshot session = sessions.ensurePersistedOwner(actor, request.sessionId());
            RiskEvent event = events.saveAndFlush(new RiskEvent(session, request.eventType(),
                    request.description(), detectedAt));
            alerts.onCreated(event);
            return new EventCreation(RiskEventResponse.from(event), true);
        }

        // PostgreSQL timestamps preserve microseconds. Use the same precision on retries.
        detectedAt = detectedAt.truncatedTo(ChronoUnit.MICROS);
        var existing = events.findWithSessionByEventKey(request.eventKey());
        if (existing.isPresent()) {
            return replay(actor, request, detectedAt, existing.get());
        }

        RiskSessionSnapshot session = sessions.ensurePersistedOwner(actor, request.sessionId());
        // A concurrent winner does not abort this transaction. A fresh SELECT can read its row.
        int inserted = events.insertIfEventKeyAbsent(session.getId(), request.eventKey(),
                request.eventType(), request.description(), detectedAt, UtcTime.now());
        RiskEvent saved = events.findWithSessionByEventKey(request.eventKey())
                .orElseThrow(() -> new IllegalStateException("Risk event missing after keyed insert"));
        if (inserted == 0) {
            return replay(actor, request, detectedAt, saved);
        }
        alerts.onCreated(saved);
        return new EventCreation(RiskEventResponse.from(saved), true);
    }

    private EventCreation replay(Actor actor, EventRequest request, LocalDateTime detectedAt, RiskEvent existing) {
        if (actor.id() != existing.getUserId() || !request.sessionId().equals(existing.getSessionId())
                || !request.eventType().equals(existing.getEventType())
                || !request.description().equals(existing.getDescription())
                || !detectedAt.equals(existing.getDetectedAt())) {
            throw ApiException.conflict("이미 사용된 위험 이벤트 키입니다.");
        }
        return new EventCreation(RiskEventResponse.from(existing), false);
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
