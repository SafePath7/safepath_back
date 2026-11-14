package com.safepath.backend.risk;

import java.util.UUID;

import com.safepath.backend.guardianrisk.ApiException;
import com.safepath.backend.guardian.GuardianModels.PermissionType;
import com.safepath.backend.guardian.GuardianRepository;
import com.safepath.backend.risk.RiskModels.*;
import com.safepath.backend.guardianrisk.CurrentUser.Actor;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskService {
    private final RiskRepository repository;
    private final GuardianRepository guardians;
    private final RiskEventNotifications notifications;
    private final RiskSessionBridge sessions;
    private final ObjectProvider<RiskLocationStorage> locationStorage;

    public RiskService(RiskRepository repository, GuardianRepository guardians, RiskEventNotifications notifications,
            RiskSessionBridge sessions, ObjectProvider<RiskLocationStorage> locationStorage) {
        this.repository = repository;
        this.guardians = guardians;
        this.notifications = notifications;
        this.sessions = sessions;
        this.locationStorage = locationStorage;
    }

    @Transactional
    public Location saveLocation(Actor actor, LocationRequest request) {
        RiskLocationStorage storage = locationStorage();
        actor.requireOwner(owner(request.sessionId()));
        return storage.saveLocation(request);
    }

    @Transactional(readOnly = true)
    public Location getLatestLocation(Actor actor, UUID sessionId) {
        RiskLocationStorage storage = locationStorage();
        long ownerId = owner(sessionId);
        if (actor.id() != ownerId && !guardians.allows(ownerId, actor.id(), PermissionType.LOCATION)) {
            throw ApiException.forbidden();
        }
        return storage.getLatestLocation(sessionId).orElseThrow(() -> ApiException.notFound("위치"));
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
        if (!actor.admin()) actor.requireOwner(userId);
        RiskEvent updated = repository.updateStatus(eventId, status);
        notifications.afterCommit(userId, updated);
        return updated;
    }

    private long owner(UUID sessionId) {
        return repository.sessionOwner(sessionId).orElseThrow(() -> ApiException.notFound("안전귀가 세션"));
    }

    private RiskLocationStorage locationStorage() {
        RiskLocationStorage storage = locationStorage.getIfAvailable();
        if (storage == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LOCATION_STORAGE_NOT_CONFIGURED",
                    "위치 저장 구조가 연결되지 않았습니다.");
        }
        return storage;
    }
}
