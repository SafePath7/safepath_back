package com.safepath.backend.user.repository;

import com.safepath.backend.user.entity.User;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// 회원 조회와 저장을 처리합니다.
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByFirebaseUid(String firebaseUid);

    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:uid)) IS NULL", nativeQuery = true)
    Boolean lockUid(@Param("uid") String uid);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.firebaseUid = :uid")
    Optional<User> findForUpdateByFirebaseUid(@Param("uid") String uid);

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM voice_profiles WHERE user_id = :userId)
                OR EXISTS (SELECT 1 FROM risk_event_medias m
                    JOIN risk_events e ON e.id = m.event_id
                    JOIN safe_return_sessions s ON s.id = e.session_id
                    WHERE s.user_id = :userId AND m.storage_url IS NOT NULL AND btrim(m.storage_url) <> '')
            """, nativeQuery = true)
    boolean hasFiles(@Param("userId") Long userId);

    @Modifying
    @Query(value = "DELETE FROM guardian_registrations WHERE user_id = :userId OR guardian_id = :userId", nativeQuery = true)
    int deleteGuardianRegistrations(@Param("userId") Long userId);

    @Modifying
    @Query(value = """
            DELETE FROM risk_event_medias WHERE event_id IN (
                SELECT e.id FROM risk_events e JOIN safe_return_sessions s ON s.id = e.session_id
                WHERE s.user_id = :userId)
            """, nativeQuery = true)
    int deleteRiskEventMedias(@Param("userId") Long userId);

    @Modifying
    @Query(value = """
            DELETE FROM risk_events WHERE session_id IN
                (SELECT id FROM safe_return_sessions WHERE user_id = :userId)
            """, nativeQuery = true)
    int deleteRiskEvents(@Param("userId") Long userId);

    @Modifying
    @Query(value = "DELETE FROM safe_return_sessions WHERE user_id = :userId", nativeQuery = true)
    int deleteSessions(@Param("userId") Long userId);

    @Modifying
    @Query(value = "DELETE FROM voice_profiles WHERE user_id = :userId", nativeQuery = true)
    int deleteVoiceProfiles(@Param("userId") Long userId);

    // 같은 UID가 이미 있으면 추가하지 않습니다.
    @Modifying
    @Query(value = """
            INSERT INTO users (firebase_uid, name, phone_hash, phone_encrypted, email)
            VALUES (:uid, :name, :hash, :encrypted, :email)
            ON CONFLICT (firebase_uid) DO NOTHING
            """, nativeQuery = true)
    int insertIfUidAbsent(@Param("uid") String uid, @Param("name") String name,
            @Param("hash") String hash, @Param("encrypted") String encrypted, @Param("email") String email);


    // 이름과 수정 시각을 갱신합니다.
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.name = :name, u.updatedAt = CURRENT_TIMESTAMP WHERE u.firebaseUid = :uid")
    int updateName(@Param("uid") String uid, @Param("name") String name);

    // 전화번호 보호 값과 수정 시각을 갱신합니다.
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.phoneHash = :hash, u.phoneEncrypted = :encrypted, u.updatedAt = CURRENT_TIMESTAMP WHERE u.firebaseUid = :uid")
    int updatePhone(@Param("uid") String uid, @Param("hash") String hash, @Param("encrypted") String encrypted);
}
