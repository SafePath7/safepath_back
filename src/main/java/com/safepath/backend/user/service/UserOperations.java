package com.safepath.backend.user.service;

import com.safepath.backend.user.repository.UserRepository;

import java.util.Optional;
import com.safepath.backend.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class UserOperations {
    private final UserRepository users;

    public Optional<Long> findUserIdByFirebaseUid(String uid) {
        return users.findByFirebaseUid(uid).map(user -> user.getId());
    }

    public Optional<UserResponse> findProfile(String uid, PhoneProtection phones) {
        return users.findByFirebaseUid(uid).map(user -> new UserResponse(user.getId(), user.getName(),
                user.getEmail(), phones.decrypt(user.getPhoneEncrypted())));
    }

    public boolean updateName(String uid, String name) {
        return users.updateName(uid, name) > 0;
    }

    public boolean updatePhone(String uid, String hash, String encrypted) {
        return users.updatePhone(uid, hash, encrypted) > 0;
    }

    public record RegistrationResult(long id, boolean created) {}

    @Transactional
    public RegistrationResult register(String uid, String name, String hash, String encrypted, String email) {
        lockUid(uid);
        Long existing = findUserIdByFirebaseUid(uid).orElse(null);
        if (existing != null) return new RegistrationResult(existing, false);
        // The native insert preserves PostgreSQL's atomic UID conflict behavior.
        int inserted = users.insertIfUidAbsent(uid, name, hash, encrypted, email);
        Long id = findUserIdByFirebaseUid(uid)
                .orElseThrow(() -> new IllegalStateException("Registration conflict without existing user"));
        return new RegistrationResult(id, inserted > 0);
    }

    private void lockUid(String uid) {
        users.lockUid(uid);
    }

    // 회원 탈퇴
    // 보호자 등록: 내가 사용자이거나 보호자인 행을 모두 삭제
    //S3 영구 삭제 코드는 아직 구현하지 않음 (아직 S3가 없음)
    @Transactional
    public void withdraw(String uid) {
        lockUid(uid);
        // 사용자 조회와 잠금 (탈퇴 되는 동안 사용자 행을 잠금)
        Long userId = users.findForUpdateByFirebaseUid(uid).map(user -> user.getId()).orElse(null);
        if (userId == null) {
            return;
        }

        // 삭제해야 할 파일이 있는지 확인
        // 1. 본인의 음성 프로필이 있는 경우
        // 2. 본인의 귀가 세션에 저장소 URL이 있는 위험 이벤트 미디어가 연결된 경우
        boolean hasFiles = users.hasFiles(userId);
        if (Boolean.TRUE.equals(hasFiles)) {
            throw new FileDeletionUnavailableException();
        }

        // 연관 DB 행 삭제 (삭제 순서)
        // 1. 보호자 관계: 본인이 사용자 또는 보호자인 관계 행
        // 2. 위험 이벤트 미디어: 본인의 귀가 세션에 연결된 미디어 행
        // 3. 위험 이벤트: 본인의 귀가 세션에 연결된 이벤트
        // 4. 귀가 세션: 본인이 소유한 세션
        // 5. 음성 프로필과 사용자 행
        users.deleteGuardianRegistrations(userId);
        users.deleteRiskEventMedias(userId);
        users.deleteRiskEvents(userId);
        users.deleteSessions(userId);
        users.deleteVoiceProfiles(userId);
        users.deleteById(userId);
    }

    public static class FileDeletionUnavailableException extends RuntimeException {}
}
