package com.safepath.backend.session.service;

import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.user.UserService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/** Internal boundary for a future Controller using Authentication supplied by the Firebase security chain. */
@Service
public class GuardianMonitoringQueryService {
	private final UserService userService;
	private final GuardianMonitoringService monitoringService;

	public GuardianMonitoringQueryService(UserService userService, GuardianMonitoringService monitoringService) {
		this.userService = Objects.requireNonNull(userService);
		this.monitoringService = Objects.requireNonNull(monitoringService);
	}

	public ActiveSession getMonitoringStatus(UUID sessionId, Authentication authentication) {
		return monitoringService.getMonitoringStatus(sessionId, authenticatedGuardianId(authentication));
	}

	public String getChannelId(UUID sessionId, Authentication authentication) {
		return monitoringService.getChannelId(sessionId, authenticatedGuardianId(authentication));
	}

	public String getGuardianChannelId(UUID sessionId, Authentication authentication) {
		return monitoringService.getGuardianChannelId(sessionId, authenticatedGuardianId(authentication));
	}

	private Long authenticatedGuardianId(Authentication authentication) {
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken
				|| !(authentication.getPrincipal() instanceof String firebaseUid) || firebaseUid.isBlank()) {
			throw new BadCredentialsException("Authenticated Firebase principal is required");
		}
		return userService.findUserIdByFirebaseUid(firebaseUid)
				.orElseThrow(() -> new AccessDeniedException("Registered user account is required"));
	}
}
