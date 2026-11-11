package com.safepath.backend.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class FcmTokenControllerTest {

	private final FcmTokenService fcmTokens = org.mockito.Mockito.mock(FcmTokenService.class);
	private final FcmTokenController controller = new FcmTokenController(fcmTokens);

	@Test
	void updatesTokenForAuthenticatedUser() {
		when(fcmTokens.registerByFirebaseUid("firebase-uid", "new-fcm-token")).thenReturn(true);

		var response = controller.updateFcmToken(
				"firebase-uid", new FcmTokenController.FcmTokenUpdateRequest("new-fcm-token"));

		assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
	}

}
