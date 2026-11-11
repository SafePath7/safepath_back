package com.safepath.backend.risk;

import java.util.UUID;

import com.safepath.backend.common.ApiException;
import com.safepath.backend.risk.RiskModels.*;
import com.safepath.backend.security.CurrentUser.Actor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskService {
    private final RiskRepository repository;
    public RiskService(RiskRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public RiskEvent createEvent(Actor actor, EventRequest request) {
        actor.requireOwner(owner(request.sessionId()));
        return repository.insertEvent(request);
    }

    @Transactional
    public RiskEvent updateEventStatus(Actor actor, long eventId, String status) {
        var event = repository.event(eventId, true).orElseThrow(() -> ApiException.notFound("위험 이벤트"));
        if (!actor.admin()) actor.requireOwner(owner(event.sessionId()));
        return repository.updateStatus(eventId, status);
    }

    private long owner(UUID sessionId) {
        return repository.sessionOwner(sessionId).orElseThrow(() -> ApiException.notFound("안전귀가 세션"));
    }
}
