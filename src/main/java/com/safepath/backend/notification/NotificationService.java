package com.safepath.backend.notification;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

	private final JdbcTemplate jdbcTemplate;

	public NotificationService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional
	public void registerFcmToken(long userId, String fcmToken) {
		if (userId <= 0) {
			throw new IllegalArgumentException("userId must be positive.");
		}
		if (fcmToken == null || fcmToken.isBlank()) {
			throw new IllegalArgumentException("fcmToken must not be blank.");
		}

		int updatedRows = jdbcTemplate.update(
				"UPDATE users SET fcm_token = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
				fcmToken,
				userId);
		if (updatedRows == 0) {
			throw new IllegalArgumentException("User does not exist: " + userId);
		}
	}

	@Transactional
	public boolean updateFcmToken(String firebaseUid, String fcmToken) {
		if (firebaseUid == null || firebaseUid.isBlank()) {
			throw new IllegalArgumentException("firebaseUid must not be blank.");
		}
		if (fcmToken == null || fcmToken.isBlank()) {
			throw new IllegalArgumentException("fcmToken must not be blank.");
		}

		return jdbcTemplate.update(
				"UPDATE users SET fcm_token = ?, updated_at = CURRENT_TIMESTAMP WHERE firebase_uid = ?",
				fcmToken,
				firebaseUid) > 0;
	}

	public Optional<SosAlertResult> sendSosToGuardians(String firebaseUid) throws FirebaseMessagingException {
		Optional<Sender> sender = findSender(firebaseUid);
		if (sender.isEmpty()) {
			return Optional.empty();
		}

		List<String> guardianTokens = findAcceptedGuardianTokens(sender.get().id());
		if (guardianTokens.isEmpty()) {
			return Optional.of(new SosAlertResult(0, 0));
		}

		Notification notification = Notification.builder()
				.setTitle("긴급 SOS 알림")
				.setBody(sender.get().name() + "님이 SOS를 요청했습니다.")
				.build();
		Map<String, String> data = Map.of("type", "SOS", "userId", String.valueOf(sender.get().id()));
		List<Message> messages = guardianTokens.stream()
				.map(token -> Message.builder()
						.setToken(token)
						.setNotification(notification)
						.putAllData(data)
						.build())
				.toList();

		BatchResponse response = FirebaseMessaging.getInstance().sendEach(messages);
		return Optional.of(new SosAlertResult(response.getSuccessCount(), response.getFailureCount()));
	}

	private Optional<Sender> findSender(String firebaseUid) {
		return jdbcTemplate.query(
				"SELECT id, name FROM users WHERE firebase_uid = ?",
				(rs, rowNum) -> new Sender(rs.getLong("id"), rs.getString("name")),
				firebaseUid).stream().findFirst();
	}

	private List<String> findAcceptedGuardianTokens(long userId) {
		return jdbcTemplate.queryForList("""
				SELECT guardian.fcm_token
				FROM guardian_registrations registration
				JOIN users guardian ON guardian.id = registration.guardian_id
				WHERE registration.user_id = ?
				  AND registration.status = 'ACCEPTED'
				  AND guardian.fcm_token IS NOT NULL
				  AND guardian.fcm_token <> ''
				""", String.class, userId);
	}

	private record Sender(long id, String name) {
	}

	public record SosAlertResult(int successCount, int failureCount) {
	}
}
