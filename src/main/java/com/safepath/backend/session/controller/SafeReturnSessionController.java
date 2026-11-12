package com.safepath.backend.session.controller;

import com.safepath.backend.session.dto.ActiveSessionResponse;
import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.service.SafeReturnSessionService;
import com.safepath.backend.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
	private final UserService userService;

	public SafeReturnSessionController(
			SafeReturnSessionService safeReturnSessionService,
			UserService userService) {
		this.safeReturnSessionService = safeReturnSessionService;
		this.userService = userService;
	}

	@PostMapping
	public ResponseEntity<ActiveSessionResponse> startSession(
			@AuthenticationPrincipal String firebaseUid) {
		ActiveSession session = safeReturnSessionService.startSession(authenticatedUserId(firebaseUid));
		return ResponseEntity.status(HttpStatus.CREATED).body(ActiveSessionResponse.from(session));
	}

	@GetMapping("/{sessionId}/status")
	public ResponseEntity<ActiveSessionResponse> getSessionStatus(
			@PathVariable UUID sessionId,
			@AuthenticationPrincipal String firebaseUid) {
		ActiveSession session = safeReturnSessionService.getSessionStatus(
				sessionId,
				authenticatedUserId(firebaseUid));
		return ResponseEntity.ok(ActiveSessionResponse.from(session));
	}

	@PatchMapping("/{sessionId}/end")
	public ResponseEntity<Void> endSession(
			@PathVariable UUID sessionId,
			@AuthenticationPrincipal String firebaseUid) {
		safeReturnSessionService.endSession(sessionId, authenticatedUserId(firebaseUid));
		return ResponseEntity.noContent().build();
	}

	private Long authenticatedUserId(String firebaseUid) {
		if (firebaseUid == null || firebaseUid.isBlank()) {
			throw new BadCredentialsException("Authenticated Firebase UID is required");
		}
		return userService.findUserIdByFirebaseUid(firebaseUid)
				.orElseThrow(() -> new AccessDeniedException("Registered user account is required"));
	}
}
