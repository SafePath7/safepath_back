package com.safepath.backend.auth;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.springframework.context.annotation.DependsOn;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

// 전달받은 Firebase ID 토큰을 검증하고, 유효하면 그 토큰의 Firebase UID를 반환
@Service
@DependsOn("firebaseConfig")
public class FirebaseTokenService {

    public String verifyIdToken(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            throw new BadCredentialsException("Firebase ID token is required"); // 토큰이 비여있는 경우
        }

        try {
            return FirebaseAuth.getInstance().verifyIdToken(idToken).getUid();  // 토큰 검증
        } catch (FirebaseAuthException e) {
            throw new BadCredentialsException("Firebase ID token verification failed", e);  // 검증 실패 시 알림
        }
    }
}
