package com.safepath.backend.risk.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import com.safepath.backend.risk.dto.EventRequest;
import com.safepath.backend.risk.dto.EventStatusRequest;
import com.safepath.backend.risk.dto.RiskEventDetailResponse;
import com.safepath.backend.risk.dto.RiskEventResponse;
import com.safepath.backend.risk.service.RiskService;
import com.safepath.backend.common.auth.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RiskController {
    private final RiskService service;
    private final CurrentUser users;

    public RiskController(RiskService service, CurrentUser users) { this.service = service; this.users = users; }

    @PostMapping("/risk-events")
    @ResponseStatus(HttpStatus.CREATED)
    public RiskEventResponse createEvent(Authentication auth, @Valid @RequestBody EventRequest request) {
        return service.createEvent(users.resolve(auth), request);
    }

    /** userId omitted: my own events. Another member's events need guardian STATUS permission. */
    @GetMapping("/risk-events")
    public List<RiskEventDetailResponse> getEvents(Authentication auth,
            @RequestParam(required = false) @Positive Long userId,
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.getEvents(users.resolve(auth), userId, sessionId, limit);
    }

    @GetMapping("/risk-events/{eventId}")
    public RiskEventDetailResponse getEvent(Authentication auth, @PathVariable @Positive long eventId) {
        return service.getEvent(users.resolve(auth), eventId);
    }

    @PatchMapping("/risk-events/{eventId}/status")
    public RiskEventResponse updateEventStatus(Authentication auth, @PathVariable @Positive long eventId,
            @Valid @RequestBody EventStatusRequest request) {
        return service.updateEvent(users.resolve(auth), eventId, request.status());
    }
}
