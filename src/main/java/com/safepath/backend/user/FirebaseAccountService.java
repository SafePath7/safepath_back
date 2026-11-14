package com.safepath.backend.user;

import com.google.firebase.auth.UserRecord;
import com.safepath.backend.user.service.FirebaseAccountLookup;
import org.springframework.stereotype.Service;

// 기존 호출 경로를 유지하며 Firebase 계정 조회를 연결합니다.
@Service
public class FirebaseAccountService extends FirebaseAccountLookup {
    @Override
    public Account verifiedAccount(UserRecord user) {
        return super.verifiedAccount(user);
    }
}
