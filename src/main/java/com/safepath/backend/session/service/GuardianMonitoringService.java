package com.safepath.backend.session.service;

import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.model.MonitoringPermissionType;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import org.springframework.security.access.AccessDeniedException;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public class GuardianMonitoringService {

	private final ActiveSessionStore activeSessionStore;
	private final GuardianPermissionService guardianPermissionService;
	private final SelectedGuardianStore selectedGuardianStore;

	public GuardianMonitoringService(
			ActiveSessionStore activeSessionStore,
			GuardianPermissionService guardianPermissionService,
			SelectedGuardianStore selectedGuardianStore) {
		this.activeSessionStore = Objects.requireNonNull(activeSessionStore);
		this.guardianPermissionService = Objects.requireNonNull(guardianPermissionService);
		this.selectedGuardianStore = Objects.requireNonNull(selectedGuardianStore);
	}

	public ActiveSession getMonitoringStatus(UUID sessionId, Long guardianId) {
		return validateMonitoringPermission(sessionId, guardianId, MonitoringPermissionType.STATUS);
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
