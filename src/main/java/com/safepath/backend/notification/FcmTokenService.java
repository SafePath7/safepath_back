package com.safepath.backend.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FcmTokenService {

	private final JdbcTemplate jdbcTemplate;

	public FcmTokenService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Stores the current device's FCM token for an existing user.
	 * This is intended to be called from user registration or token-refresh flows,
	 * not exposed as a notification-sending API.
	 */
	@Transactional
	public void register(long userId, String fcmToken) {
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
}
