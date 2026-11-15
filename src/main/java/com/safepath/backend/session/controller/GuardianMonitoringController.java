package com.safepath.backend.session.controller;

import com.safepath.backend.session.dto.ActiveSessionResponse;
import com.safepath.backend.session.dto.MonitoringChannelResponse;
import com.safepath.backend.session.service.GuardianMonitoringQueryService;
import com.safepath.backend.websocket.dto.LocationUpdateRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/guardian/monitoring/sessions")
public class GuardianMonitoringController {

	private final GuardianMonitoringQueryService queryService;

	public GuardianMonitoringController(GuardianMonitoringQueryService queryService) {
		this.queryService = queryService;
	}

	@GetMapping("/{sessionId}/status")
	public ResponseEntity<ActiveSessionResponse> getMonitoringStatus(
			@PathVariable UUID sessionId, Authentication authentication) {
		return ResponseEntity.ok(ActiveSessionResponse.from(queryService.getMonitoringStatus(sessionId, authentication)));
	}

	@GetMapping("/{sessionId}/location")
	public ResponseEntity<LocationUpdateRequest> getMonitoredLocation(
			@PathVariable UUID sessionId, Authentication authentication) {
		return queryService.getMonitoredLocation(sessionId, authentication)
				.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
	}

	@GetMapping("/{sessionId}/channel")
	public ResponseEntity<MonitoringChannelResponse> getMonitoringChannel(
			@PathVariable UUID sessionId, Authentication authentication) {
		return ResponseEntity.ok(new MonitoringChannelResponse(queryService.getGuardianChannelId(sessionId, authentication)));
	}
}
