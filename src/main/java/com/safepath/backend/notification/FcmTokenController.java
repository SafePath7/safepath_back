package com.safepath.backend.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class FcmTokenController {

	private final FcmTokenService fcmTokenService;

	public FcmTokenController(FcmTokenService fcmTokenService) {
		this.fcmTokenService = fcmTokenService;
	}

	@PutMapping("/fcm-token")
	public ResponseEntity<Void> updateFcmToken(
			@AuthenticationPrincipal String firebaseUid,
			@Valid @RequestBody FcmTokenUpdateRequest request) {
		if (!fcmTokenService.registerByFirebaseUid(firebaseUid, request.fcmToken())) {
			return ResponseEntity.notFound().build();
		}

		return ResponseEntity.noContent().build();
	}

	public record FcmTokenUpdateRequest(@NotBlank String fcmToken) {
	}
}
