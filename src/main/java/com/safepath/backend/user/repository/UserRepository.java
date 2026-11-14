package com.safepath.backend.user.repository;

import com.safepath.backend.user.entity.User;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// 회원 조회와 저장을 처리합니다.
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByFirebaseUid(String firebaseUid);

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
