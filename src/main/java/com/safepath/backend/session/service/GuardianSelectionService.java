package com.safepath.backend.session.service;

import com.safepath.backend.session.model.MonitoringPermissionType;
import com.safepath.backend.session.exception.GuardianSelectionDeniedException;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class GuardianSelectionService {
	private final ActiveSessionStore activeSessionStore;
	private final SelectedGuardianStore selectedGuardianStore;
	private final GuardianPermissionService permissionService;

	public GuardianSelectionService(ActiveSessionStore activeSessionStore,
			SelectedGuardianStore selectedGuardianStore, GuardianPermissionService permissionService) {
		this.activeSessionStore = Objects.requireNonNull(activeSessionStore);
		this.selectedGuardianStore = Objects.requireNonNull(selectedGuardianStore);
		this.permissionService = Objects.requireNonNull(permissionService);
	}

	public Set<Long> validateForStart(Long ownerId, Collection<Long> guardianIds) {
		if (ownerId == null || ownerId <= 0 || guardianIds == null || guardianIds.isEmpty()) {
			throw new IllegalArgumentException("Owner and at least one guardian are required");
		}
		Set<Long> uniqueIds = new LinkedHashSet<>(guardianIds);
		for (Long guardianId : uniqueIds) {
			validateGuardianId(guardianId);
			if (ownerId.equals(guardianId)) {
				throw new GuardianSelectionDeniedException("Owner cannot be selected as guardian");
			}
		}
		for (Long guardianId : uniqueIds) {
			if (!permissionService.hasAcceptedRelationship(ownerId, guardianId)
					|| !permissionService.hasPermission(ownerId, guardianId, MonitoringPermissionType.LOCATION)) {
				throw new GuardianSelectionDeniedException("Guardian selection denied");
			}
		}
		return Set.copyOf(uniqueIds);
	}

	public Set<Long> getSelectedGuardians(UUID sessionId, Long ownerId) {
		synchronized (activeSessionStore) {
			requireOwner(sessionId, ownerId);
			return selectedGuardianStore.findBySessionId(sessionId);
		}
	}

	/** ownerId must be resolved from the authenticated Firebase principal. */
	public void selectGuardian(UUID sessionId, Long ownerId, Long guardianId) {
		validateGuardianId(guardianId);
		synchronized (activeSessionStore) {
			requireOwner(sessionId, ownerId);
			throw new AccessDeniedException("Guardians cannot be changed during an active session");
		}
	}

	public void deselectGuardian(UUID sessionId, Long ownerId, Long guardianId) {
		validateGuardianId(guardianId);
		synchronized (activeSessionStore) {
			requireOwner(sessionId, ownerId);
			throw new AccessDeniedException("Guardians cannot be changed during an active session");
		}
	}

	private void requireOwner(UUID sessionId, Long ownerId) {
		if (sessionId == null || ownerId == null || ownerId <= 0) {
			throw new IllegalArgumentException("Session and owner are required");
		}
		var session = activeSessionStore.findById(sessionId)
				.orElseThrow(() -> new NoSuchElementException("Active session not found"));
		if (!session.userId().equals(ownerId)) {
			throw new SecurityException("User is not the owner of this active session");
		}
	}

	private void validateGuardianId(Long guardianId) {
		if (guardianId == null || guardianId <= 0) {
			throw new IllegalArgumentException("guardianId must be a positive number");
		}
	}
}
