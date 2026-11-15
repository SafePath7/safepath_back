package com.safepath.backend.session.service;

import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class GuardianSelectionService {
	private final ActiveSessionStore activeSessionStore;
	private final SafeReturnSessionService sessionService;
	private final SelectedGuardianStore selectedGuardianStore;
	private final GuardianPermissionService permissionService;

	public GuardianSelectionService(ActiveSessionStore activeSessionStore, SafeReturnSessionService sessionService,
			SelectedGuardianStore selectedGuardianStore, GuardianPermissionService permissionService) {
		this.activeSessionStore = Objects.requireNonNull(activeSessionStore);
		this.sessionService = Objects.requireNonNull(sessionService);
		this.selectedGuardianStore = Objects.requireNonNull(selectedGuardianStore);
		this.permissionService = Objects.requireNonNull(permissionService);
	}

	/** ownerId must be resolved from the authenticated Firebase principal. */
	public void selectGuardian(UUID sessionId, Long ownerId, Long guardianId) {
		validateGuardianId(guardianId);
		synchronized (activeSessionStore) {
			sessionService.getSessionStatus(sessionId, ownerId);
			if (guardianId.equals(ownerId) || !permissionService.hasAcceptedRelationship(ownerId, guardianId)) {
				throw new AccessDeniedException("Guardian selection denied");
			}
			Set<Long> guardianIds = new HashSet<>(selectedGuardianStore.findBySessionId(sessionId));
			guardianIds.add(guardianId);
			selectedGuardianStore.save(sessionId, guardianIds);
		}
	}

	public void deselectGuardian(UUID sessionId, Long ownerId, Long guardianId) {
		validateGuardianId(guardianId);
		synchronized (activeSessionStore) {
			sessionService.getSessionStatus(sessionId, ownerId);
			Set<Long> guardianIds = new HashSet<>(selectedGuardianStore.findBySessionId(sessionId));
			guardianIds.remove(guardianId);
			selectedGuardianStore.save(sessionId, guardianIds);
		}
	}

	private void validateGuardianId(Long guardianId) {
		if (guardianId == null || guardianId <= 0) {
			throw new IllegalArgumentException("guardianId must be a positive number");
		}
	}
}
