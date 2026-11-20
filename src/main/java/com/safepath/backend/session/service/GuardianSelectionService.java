package com.safepath.backend.session.service;

import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class GuardianSelectionService {
	private final ActiveSessionStore activeSessionStore;
	private final SelectedGuardianStore selectedGuardianStore;

	public GuardianSelectionService(ActiveSessionStore activeSessionStore,
			SelectedGuardianStore selectedGuardianStore) {
		this.activeSessionStore = Objects.requireNonNull(activeSessionStore);
		this.selectedGuardianStore = Objects.requireNonNull(selectedGuardianStore);
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
