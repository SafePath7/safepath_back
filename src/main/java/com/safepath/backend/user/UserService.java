package com.safepath.backend.user;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final JdbcTemplate jdbc;

    public UserService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> findUserIdByFirebaseUid(String uid) {
        return jdbc.query("SELECT id FROM users WHERE firebase_uid = ?",
                (rs, rowNum) -> rs.getLong("id"), uid).stream().findFirst();
    }

    // 이름 수정
    public boolean updateName(String uid, String name) {
        return jdbc.update("""
                UPDATE users SET name = ?, updated_at = CURRENT_TIMESTAMP WHERE firebase_uid = ?
                """, name, uid) > 0;
    }

    // 전화번호 수정
    public boolean updatePhone(String uid, String hash, String encrypted) {
        return jdbc.update("""
                UPDATE users SET phone_hash = ?, phone_encrypted = ?, updated_at = CURRENT_TIMESTAMP
                WHERE firebase_uid = ?
                """, hash, encrypted, uid) > 0;
    }

    // 회원 탈퇴
    // 보호자 등록: 내가 사용자이거나 보호자인 행을 모두 삭제
    //S3 영구 삭제 코드는 아직 구현하지 않음 (아직 S3가 없음)
    @Transactional
    public void withdraw(String uid) {
        // 사용자 조회와 잠금 (탈퇴 되는 동안 사용자 행을 잠금)
        Long userId = jdbc.query("SELECT id FROM users WHERE firebase_uid = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong("id"), uid).stream().findFirst().orElse(null);
        if (userId == null) {
            return;
        }

        // 삭제해야 할 파일이 있는지 확인
        // 1. 본인의 음성 프로필이 있는 경우
        // 2. 본인의 귀가 세션에 저장소 URL이 있는 위험 이벤트 미디어가 연결된 경우
        Boolean hasFiles = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM voice_profiles WHERE user_id = ?)
                    OR EXISTS (SELECT 1 FROM risk_event_medias m
                        JOIN risk_events e ON e.id = m.event_id
                        JOIN safe_return_sessions s ON s.id = e.session_id
                        WHERE s.user_id = ? AND m.storage_url IS NOT NULL AND btrim(m.storage_url) <> '')
                """, Boolean.class, userId, userId);
        if (Boolean.TRUE.equals(hasFiles)) {
            throw new FileDeletionUnavailableException();
        }

        // 연관 DB 행 삭제 (삭제 순서)
        // 1. 보호자 관계: 본인이 사용자 또는 보호자인 관계 행
        // 2. 위험 이벤트 미디어: 본인의 귀가 세션에 연결된 미디어 행
        // 3. 위험 이벤트: 본인의 귀가 세션에 연결된 이벤트
        // 4. 귀가 세션: 본인이 소유한 세션
        // 5. 음성 프로필과 사용자 행
        jdbc.update("DELETE FROM guardian_registrations WHERE user_id = ? OR guardian_id = ?", userId, userId);
        jdbc.update("""
                DELETE FROM risk_event_medias WHERE event_id IN (
                    SELECT e.id FROM risk_events e JOIN safe_return_sessions s ON s.id = e.session_id
                    WHERE s.user_id = ?)
                """, userId);
        jdbc.update("""
                DELETE FROM risk_events WHERE session_id IN
                    (SELECT id FROM safe_return_sessions WHERE user_id = ?)
                """, userId);
        jdbc.update("DELETE FROM safe_return_sessions WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM voice_profiles WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    public static class FileDeletionUnavailableException extends RuntimeException {}
}
