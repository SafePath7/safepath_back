package com.safepath.backend.session.service;

import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.model.MonitoringPermissionType;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import com.safepath.backend.session.store.LatestLocationStore;
import com.safepath.backend.websocket.dto.LocationUpdateRequest;
import java.util.Optional;
import org.springframework.security.access.AccessDeniedException;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public class GuardianMonitoringService {

	private final ActiveSessionStore activeSessionStore;
	private final GuardianPermissionService guardianPermissionService;
	private final SelectedGuardianStore selectedGuardianStore;
	private final LatestLocationStore latestLocationStore;

	public GuardianMonitoringService(
			ActiveSessionStore activeSessionStore,
			GuardianPermissionService guardianPermissionService,
			SelectedGuardianStore selectedGuardianStore,
			LatestLocationStore latestLocationStore) {
		this.activeSessionStore = Objects.requireNonNull(activeSessionStore);
		this.guardianPermissionService = Objects.requireNonNull(guardianPermissionService);
		this.selectedGuardianStore = Objects.requireNonNull(selectedGuardianStore);
		this.latestLocationStore = Objects.requireNonNull(latestLocationStore);
	}

	public ActiveSession getMonitoringStatus(UUID sessionId, Long guardianId) {
		return validateMonitoringPermission(sessionId, guardianId, MonitoringPermissionType.STATUS);
	}

	public Optional<LocationUpdateRequest> getMonitoredLocation(UUID sessionId, Long guardianId) {
		synchronized (activeSessionStore) {
			validateMonitoringPermission(sessionId, guardianId, MonitoringPermissionType.LOCATION);
			return latestLocationStore.findBySessionId(sessionId);
		}
	}

	/** guardianId must be resolved server-side from the authenticated Firebase principal. */
	public ActiveSession validateMonitoringPermission(
			UUID sessionId, Long guardianId, MonitoringPermissionType permissionType) {
		if (sessionId == null) {
			throw new IllegalArgumentException("sessionId must not be null");
		}
		if (guardianId == null || guardianId <= 0) {
			throw new IllegalArgumentException("guardianId must be a positive number");
		}
		if (permissionType == null) {
			throw new IllegalArgumentException("permissionType must not be null");
		}

		ActiveSession session = activeSessionStore.findById(sessionId)
				.orElseThrow(() -> new NoSuchElementException("Active session not found"));
		if (session.userId().equals(guardianId)) {
			throw new AccessDeniedException("Monitoring access denied");
		}
		if (!guardianPermissionService.hasAcceptedRelationship(session.userId(), guardianId)) {
			throw new AccessDeniedException("Monitoring access denied");
		}
		if (!guardianPermissionService.hasPermission(session.userId(), guardianId, permissionType)) {
			throw new AccessDeniedException("Monitoring access denied");
		}
		if (!selectedGuardianStore.isSelectedGuardian(sessionId, guardianId)) {
			throw new AccessDeniedException("Monitoring access denied");
		}
		return session;
	}
}
