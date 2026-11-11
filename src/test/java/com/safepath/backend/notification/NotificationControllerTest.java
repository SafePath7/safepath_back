package com.safepath.backend.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class NotificationControllerTest {

	private final NotificationService notifications = org.mockito.Mockito.mock(NotificationService.class);
	private final NotificationController controller = new NotificationController(notifications);

	@Test
	void updatesTokenForAuthenticatedUser() {
		when(notifications.updateFcmToken("firebase-uid", "new-fcm-token")).thenReturn(true);

		var response = controller.updateFcmToken(
				"firebase-uid", new NotificationController.FcmTokenUpdateRequest("new-fcm-token"));

		assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
	}

	@Test
	void sendsSosToAcceptedGuardians() throws Exception {
		when(notifications.sendSosToGuardians("firebase-uid"))
				.thenReturn(Optional.of(new NotificationService.SosAlertResult(2, 0)));

		var response = controller.sendSos("firebase-uid");

		assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
		assertEquals(new NotificationController.SosAlertResponse(2, 0), response.getBody());
	}
}
