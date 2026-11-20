package com.safepath.backend.monitoring.service;

import com.safepath.backend.guardian.service.GuardianRegistrationService;
import com.safepath.backend.guardian.entity.PermissionType;
import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import com.safepath.backend.session.store.SessionChannelStore;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class GuardianMonitoringService {

	private final ActiveSessionStore activeSessionStore;
	private final GuardianRegistrationService guardianPermissionService;
	private final SelectedGuardianStore selectedGuardianStore;
	private final SessionChannelStore sessionChannelStore;

	public GuardianMonitoringService(
			ActiveSessionStore activeSessionStore,
			GuardianRegistrationService guardianPermissionService,
			SelectedGuardianStore selectedGuardianStore,
			SessionChannelStore sessionChannelStore) {
		this.activeSessionStore = Objects.requireNonNull(activeSessionStore);
		this.guardianPermissionService = Objects.requireNonNull(guardianPermissionService);
		this.selectedGuardianStore = Objects.requireNonNull(selectedGuardianStore);
		this.sessionChannelStore = Objects.requireNonNull(sessionChannelStore);
	}

	/** requesterId is a trusted internal ID; HTTP callers use GuardianMonitoringQueryService. */
	public String getChannelId(UUID sessionId, Long requesterId) {
		if (sessionId == null || requesterId == null || requesterId <= 0) {
			throw new IllegalArgumentException("Session and requester are required");
		}
		synchronized (activeSessionStore) {
			ActiveSession session = activeSessionStore.findById(sessionId)
					.orElseThrow(() -> new NoSuchElementException("Active session not found"));
			if (!session.userId().equals(requesterId)) {
				validateMonitoringPermission(sessionId, requesterId);
			}
			return sessionChannelStore.findBySessionId(sessionId)
					.orElseThrow(() -> new NoSuchElementException("Session channel not found"));
		}
	}

	/**
	 * Guardian-only boundary for a future STOMP hook. guardianId must come from the verified server session.
	 * This does not decide the owner's subscription policy or register a broker subscription.
	 */
	public ActiveSession validateGuardianLocationSubscription(String channelId, Long guardianId) {
		if (channelId == null || channelId.isBlank() || guardianId == null || guardianId <= 0) {
			throw new IllegalArgumentException("Channel and authenticated guardian are required");
		}
		synchronized (activeSessionStore) {
			UUID sessionId = sessionChannelStore.findSessionIdByChannelId(channelId)
					.orElseThrow(() -> new NoSuchElementException("Session channel not found"));
			if (sessionChannelStore.findBySessionId(sessionId).filter(channelId::equals).isEmpty()) {
				throw new AccessDeniedException("Session channel mapping does not match");
			}
			return validateMonitoringPermission(sessionId, guardianId);
		}
	}

	public String getGuardianChannelId(UUID sessionId, Long guardianId) {
		if (sessionId == null || guardianId == null || guardianId <= 0) {
			throw new IllegalArgumentException("Session and authenticated guardian are required");
		}
		synchronized (activeSessionStore) {
			ActiveSession session = activeSessionStore.findById(sessionId)
					.orElseThrow(() -> new NoSuchElementException("Active session not found"));
			if (session.userId().equals(guardianId)
					|| !selectedGuardianStore.isSelectedGuardian(sessionId, guardianId)) {
				throw new AccessDeniedException("Monitoring access denied");
			}
			String channelId = sessionChannelStore.findBySessionId(sessionId)
					.orElseThrow(() -> new NoSuchElementException("Session channel not found"));
			if (sessionChannelStore.findSessionIdByChannelId(channelId).filter(sessionId::equals).isEmpty()) {
				throw new AccessDeniedException("Session channel mapping does not match");
			}
			return channelId;
		}
	}

	public ActiveSession getMonitoringStatus(UUID sessionId, Long guardianId) {
		return validateMonitoringPermission(sessionId, guardianId);
	}

	/** guardianId must be resolved server-side from the authenticated Firebase principal. */
	public ActiveSession validateMonitoringPermission(
			UUID sessionId, Long guardianId) {
		if (sessionId == null) {
			throw new IllegalArgumentException("sessionId must not be null");
		}
		if (guardianId == null || guardianId <= 0) {
			throw new IllegalArgumentException("guardianId must be a positive number");
		}

		ActiveSession session = activeSessionStore.findById(sessionId)
				.orElseThrow(() -> new NoSuchElementException("Active session not found"));
		if (session.userId().equals(guardianId)) {
			throw new AccessDeniedException("Monitoring access denied");
		}
		if (!guardianPermissionService.hasGuardianPermission(session.userId(), guardianId, PermissionType.LOCATION)) {
			throw new AccessDeniedException("Monitoring access denied");
		}
		if (!selectedGuardianStore.isSelectedGuardian(sessionId, guardianId)) {
			throw new AccessDeniedException("Monitoring access denied");
		}
		return session;
	}
}
