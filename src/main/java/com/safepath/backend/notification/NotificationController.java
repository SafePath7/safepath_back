package com.safepath.backend.notification;

import com.google.firebase.messaging.FirebaseMessagingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

	private final NotificationService notificationService;

	public NotificationController(NotificationService notificationService) {
		this.notificationService = notificationService;
	}

	@PutMapping("/fcm-token")
	public ResponseEntity<Void> updateFcmToken(
			@AuthenticationPrincipal String firebaseUid,
			@Valid @RequestBody FcmTokenUpdateRequest request) {
		if (!notificationService.updateFcmToken(firebaseUid, request.fcmToken())) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/sos")
	public ResponseEntity<SosAlertResponse> sendSos(@AuthenticationPrincipal String firebaseUid)
			throws FirebaseMessagingException {
		Optional<NotificationService.SosAlertResult> result = notificationService.sendSosToGuardians(firebaseUid);
		if (result.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.body(new SosAlertResponse(result.get().successCount(), result.get().failureCount()));
	}

	public record FcmTokenUpdateRequest(@NotBlank String fcmToken) {
	}

	public record SosAlertResponse(int successCount, int failureCount) {
	}
}
