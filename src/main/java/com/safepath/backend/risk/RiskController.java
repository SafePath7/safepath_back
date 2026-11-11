package com.safepath.backend.risk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import com.safepath.backend.risk.RiskModels.*;
import com.safepath.backend.security.CurrentUser;

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
    public RiskEvent createEvent(Authentication auth, @Valid @RequestBody EventRequest request) {
        return service.createEvent(users.resolve(auth), request);
    }

    @PatchMapping("/risk-events/{eventId}/status")
    public RiskEvent updateEventStatus(Authentication auth, @PathVariable @Positive long eventId,
            @Valid @RequestBody EventStatusRequest request) {
        return service.updateEventStatus(users.resolve(auth), eventId, request.status());
    }
}
