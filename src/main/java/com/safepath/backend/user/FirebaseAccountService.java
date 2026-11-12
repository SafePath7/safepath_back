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
        String phoneNumber = linkedPhoneNumber(user);
        // 가입에 필요한 조건 확인
        if (google == null || google.getEmail() == null || google.getEmail().isBlank()
                || !user.isEmailVerified() || !google.getEmail().equalsIgnoreCase(user.getEmail())
                || phoneNumber == null) {
            return null;
        }
        // 확인된 정보 반환
        return new Account(google.getDisplayName(), google.getEmail(), phoneNumber);
    }

    // Firebase에서 UID에 연결된 전화번호를 조회
    public String getLinkedPhoneNumber(String uid) throws FirebaseAuthException {
        return linkedPhoneNumber(FirebaseAuth.getInstance().getUser(uid));
    }

    // 사용자 전화번호가 비어 있지 않고, phone 제공자에 연결된 번호와 일치할 때만 번호를 반환
    private String linkedPhoneNumber(UserRecord user) {
        String phoneNumber = user.getPhoneNumber();
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return null;
        }
        return Arrays.stream(user.getProviderData())
                .anyMatch(provider -> "phone".equals(provider.getProviderId())
                        && phoneNumber.equals(provider.getPhoneNumber())) ? phoneNumber : null;
    }

    public record Account(String displayName, String email, String phoneNumber) {}
}
