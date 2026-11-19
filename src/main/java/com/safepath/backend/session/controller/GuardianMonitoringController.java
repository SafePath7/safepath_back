package com.safepath.backend.session.controller;

import com.safepath.backend.session.dto.SelectedRoute;
import com.safepath.backend.session.dto.MonitoringChannelResponse;
import com.safepath.backend.session.service.GuardianMonitoringQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.time.Instant;

@RestController
@RequestMapping("/api/guardian/monitoring/sessions")
public class GuardianMonitoringController {

	private final GuardianMonitoringQueryService queryService;

	public GuardianMonitoringController(GuardianMonitoringQueryService queryService) {
		this.queryService = queryService;
	}

	@GetMapping("/{sessionId}/status")
	public ResponseEntity<GuardianStatusResponse> getMonitoringStatus(
			@PathVariable UUID sessionId, Authentication authentication) {
		var session = queryService.getMonitoringStatus(sessionId, authentication);
		return ResponseEntity.ok(new GuardianStatusResponse(session.id(), session.startedAt()));
	}

	@GetMapping("/{sessionId}/route")
	public ResponseEntity<SelectedRoute> getMonitoringRoute(
			@PathVariable UUID sessionId, Authentication authentication) {
		var route = queryService.getMonitoringRoute(sessionId, authentication).selectedRoute();
		return route == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(route);
	}

	public record GuardianStatusResponse(UUID id, Instant startedAt) {}

	@GetMapping("/{sessionId}/channel")
	public ResponseEntity<MonitoringChannelResponse> getMonitoringChannel(
			@PathVariable UUID sessionId, Authentication authentication) {
		return ResponseEntity.ok(new MonitoringChannelResponse(queryService.getGuardianChannelId(sessionId, authentication)));
	}
}
