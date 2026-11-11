package com.safepath.backend.user;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserInfo;
import com.google.firebase.auth.UserRecord;
import java.util.Arrays;
import org.springframework.stereotype.Service;

// Firebase에서 사용자의 계정 정보를 가져와 가입에 필요한 조건을 확인
@Service
public class FirebaseAccountService {
    // Firebase에서 계정 정보 가져오기
    public Account getAccount(String uid) throws FirebaseAuthException {
        return verifiedAccount(FirebaseAuth.getInstance().getUser(uid));
    }

    Account verifiedAccount(UserRecord user) {
        // 연결된 Google 계정 찾기
        UserInfo google = Arrays.stream(user.getProviderData())
                .filter(provider -> "google.com".equals(provider.getProviderId()))
                .findFirst().orElse(null);
        // 전화번호 연결 확인
        boolean linkedPhone = Arrays.stream(user.getProviderData())
                .anyMatch(provider -> "phone".equals(provider.getProviderId())
                        && user.getPhoneNumber() != null
                        && user.getPhoneNumber().equals(provider.getPhoneNumber()));
        // 가입에 필요한 조건 확인
        if (google == null || google.getEmail() == null || google.getEmail().isBlank()
                || !user.isEmailVerified() || !google.getEmail().equalsIgnoreCase(user.getEmail())
                || user.getPhoneNumber() == null || user.getPhoneNumber().isBlank() || !linkedPhone) {
            return null;
        }
        // 확인된 정보 반환
        return new Account(google.getDisplayName(), google.getEmail(), user.getPhoneNumber());
    }

    public record Account(String displayName, String email, String phoneNumber) {}
}
