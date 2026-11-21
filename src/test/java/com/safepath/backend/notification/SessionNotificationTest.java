package com.safepath.backend.notification;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionNotificationTest {
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final NotificationService notifications = new NotificationService(jdbc);

	@Test
	void sendsStartAndEndOnlyToSelectedGuardiansWithSessionData() throws Exception {
		ResultSet row = mock(ResultSet.class);
		when(row.getString("requester_name")).thenReturn("User");
		when(row.getString("fcm_token")).thenReturn("guardian-token");
		when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), anyLong()))
				.thenAnswer(invocation -> List.of(((RowMapper<?>) invocation.getArgument(1)).mapRow(row, 0)));
		FirebaseMessaging firebase = mock(FirebaseMessaging.class);
		UUID sessionId = UUID.randomUUID();
		try (var staticFirebase = mockStatic(FirebaseMessaging.class)) {
			staticFirebase.when(FirebaseMessaging::getInstance).thenReturn(firebase);
			assertEquals(new NotificationService.NotificationResult(1, 0),
					notifications.sendSessionStartedNotification(42L, sessionId, Set.of(43L)));
			assertEquals(new NotificationService.NotificationResult(1, 0),
					notifications.sendSessionEndedNotification(42L, sessionId, Set.of(43L)));
		}
		verify(jdbc, times(2)).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
				org.mockito.ArgumentMatchers.eq(43L), org.mockito.ArgumentMatchers.eq(42L));
		ArgumentCaptor<Message> messages = ArgumentCaptor.forClass(Message.class);
		verify(firebase, times(2)).send(messages.capture());
		assertEquals(Map.of("type", "SESSION_STARTED", "userId", "42", "sessionId", sessionId.toString()),
				ReflectionTestUtils.getField(messages.getAllValues().get(0), "data"));
		assertEquals(Map.of("type", "SESSION_ENDED", "userId", "42", "sessionId", sessionId.toString()),
				ReflectionTestUtils.getField(messages.getAllValues().get(1), "data"));
		assertEquals("guardian-token", ReflectionTestUtils.getField(messages.getValue(), "token"));
	}

	@Test
	void missingTokenIsSkippedAndLookupFailureDoesNotStopOtherRecipients() {
		when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), anyLong()))
				.thenAnswer(invocation -> {
					long guardianId = invocation.getArgument(2);
					if (guardianId == 43L) throw new IllegalStateException("Database unavailable");
					return List.of();
				});
		assertEquals(new NotificationService.NotificationResult(0, 1),
				notifications.sendSessionStartedNotification(42L, UUID.randomUUID(), Set.of(43L, 44L)));
		verify(jdbc, times(2)).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), anyLong());
	}
}
