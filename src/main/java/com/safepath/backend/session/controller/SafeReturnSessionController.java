package com.safepath.backend.session.controller;

import com.safepath.backend.security.local.LocalTestUserPrincipal;
import com.safepath.backend.session.dto.ActiveSessionResponse;
import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.service.SafeReturnSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/safe-return/sessions")
public class SafeReturnSessionController {

	private final SafeReturnSessionService safeReturnSessionService;

	public SafeReturnSessionController(SafeReturnSessionService safeReturnSessionService) {
		this.safeReturnSessionService = safeReturnSessionService;
	}

	@PostMapping
	public ResponseEntity<ActiveSessionResponse> startSession(Authentication authentication) {
		ActiveSession session = safeReturnSessionService.startSession(authenticatedUserId(authentication));
		return ResponseEntity.status(HttpStatus.CREATED).body(ActiveSessionResponse.from(session));
	}

	@GetMapping("/{sessionId}/status")
	public ResponseEntity<ActiveSessionResponse> getSessionStatus(
			@PathVariable UUID sessionId,
			Authentication authentication) {
		ActiveSession session = safeReturnSessionService.getSessionStatus(
				sessionId,
				authenticatedUserId(authentication));
		return ResponseEntity.ok(ActiveSessionResponse.from(session));
	}

	@PatchMapping("/{sessionId}/end")
	public ResponseEntity<Void> endSession(
			@PathVariable UUID sessionId,
			Authentication authentication) {
		safeReturnSessionService.endSession(sessionId, authenticatedUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	private Long authenticatedUserId(Authentication authentication) {
		if (authentication == null
				|| !authentication.isAuthenticated()
				|| !(authentication.getPrincipal() instanceof LocalTestUserPrincipal principal)) {
			throw new BadCredentialsException("Unsupported authentication principal");
		}
		return principal.getUserId();
	}
}
