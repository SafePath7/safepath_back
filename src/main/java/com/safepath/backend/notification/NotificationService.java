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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class NotificationService {

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

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

	/**
	 * Notifies accepted guardians after a route-deviation risk event is updated.
	 * A delivery failure is returned rather than thrown so the risk-event update remains saved.
	 */
	public NotificationResult notifyGuardianOfRouteDeviation(long userId) {
		List<String> guardianTokens = findAcceptedGuardianTokens(userId);
		if (guardianTokens.isEmpty()) {
			return new NotificationResult(0, 0);
		}

		Notification notification = Notification.builder()
				.setTitle("경로 이탈 감지")
				.setBody("보호 대상자가 설정된 경로를 이탈했습니다. 위치를 확인해 주세요.")
				.build();
		Map<String, String> data = Map.of("type", "ROUTE_DEVIATION", "userId", String.valueOf(userId));
		List<Message> messages = guardianTokens.stream()
				.map(token -> Message.builder()
						.setToken(token)
						.setNotification(notification)
						.putAllData(data)
						.build())
				.toList();

		try {
			BatchResponse response = FirebaseMessaging.getInstance().sendEach(messages);
			return new NotificationResult(response.getSuccessCount(), response.getFailureCount());
		} catch (FirebaseMessagingException e) {
			log.warn("Failed to send route-deviation notification. userId={}", userId, e);
			return new NotificationResult(0, guardianTokens.size());
		}
	}

	/**
	 * Sends a notification to the requested guardian after a guardian-connection request is saved.
	 * A delivery failure does not throw so it cannot undo the saved connection request.
	 */
	public NotificationResult sendGuardianRequestNotification(long requesterId, long guardianId) {
		Optional<GuardianRequestTarget> target = findGuardianRequestTarget(requesterId, guardianId);
		if (target.isEmpty() || target.get().fcmToken() == null || target.get().fcmToken().isBlank()) {
			return new NotificationResult(0, 0);
		}

		Message message = Message.builder()
				.setToken(target.get().fcmToken())
				.setNotification(Notification.builder()
						.setTitle("보호자 연결 요청")
						.setBody(target.get().requesterName() + "님이 보호자 연결을 요청했습니다.")
						.build())
				.putAllData(Map.of("type", "GUARDIAN_REQUEST", "requesterId", String.valueOf(requesterId)))
				.build();

		try {
			FirebaseMessaging.getInstance().send(message);
			return new NotificationResult(1, 0);
		} catch (FirebaseMessagingException e) {
			log.warn("Failed to send guardian request notification. requesterId={}, guardianId={}", requesterId, guardianId, e);
			return new NotificationResult(0, 1);
		}
	}

	/**
	 * Sends a reminder three days before risk-event media is scheduled for deletion.
	 * The scheduler will later decide when to invoke this method.
	 */
	public NotificationResult sendDeletionReminderNotification(long userId) {
		Optional<String> fcmToken = findFcmTokenByUserId(userId);
		if (fcmToken.isEmpty() || fcmToken.get().isBlank()) {
			return new NotificationResult(0, 0);
		}

		Message message = Message.builder()
				.setToken(fcmToken.get())
				.setNotification(Notification.builder()
						.setTitle("데이터 삭제 예정 안내")
						.setBody("위험 상황 관련 음성 및 사진 데이터가 3일 후 자동 삭제됩니다.")
						.build())
				.putAllData(Map.of("type", "DELETION_REMINDER", "userId", String.valueOf(userId)))
				.build();

		try {
			FirebaseMessaging.getInstance().send(message);
			return new NotificationResult(1, 0);
		} catch (FirebaseMessagingException e) {
			log.warn("Failed to send deletion reminder notification. userId={}", userId, e);
			return new NotificationResult(0, 1);
		}
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

	private Optional<GuardianRequestTarget> findGuardianRequestTarget(long requesterId, long guardianId) {
		return jdbcTemplate.query("""
				SELECT requester.name AS requester_name, guardian.fcm_token
				FROM users requester
				JOIN users guardian ON guardian.id = ?
				WHERE requester.id = ?
				""", (rs, rowNum) -> new GuardianRequestTarget(
						rs.getString("requester_name"), rs.getString("fcm_token")), guardianId, requesterId)
				.stream()
				.findFirst();
	}

	private Optional<String> findFcmTokenByUserId(long userId) {
		return jdbcTemplate.queryForList(
				"SELECT fcm_token FROM users WHERE id = ? AND fcm_token IS NOT NULL AND fcm_token <> ''",
				String.class,
				userId).stream().findFirst();
	}

	private record Sender(long id, String name) {
	}

	private record GuardianRequestTarget(String requesterName, String fcmToken) {
	}

	public record SosAlertResult(int successCount, int failureCount) {
	}

	public record NotificationResult(int successCount, int failureCount) {
	}
}
