package com.safepath.backend.guardianrisk;

import java.util.function.Supplier;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Reads the trusted user record because the shared authentication has no role authorities. */
@Component
public class FirebaseAdminRoleLookup {
    private final Supplier<FirebaseAuth> firebaseAuth;

    public FirebaseAdminRoleLookup() {
        this(FirebaseAuth::getInstance);
    }

    FirebaseAdminRoleLookup(Supplier<FirebaseAuth> firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    public boolean isAdmin(String uid) {
        try {
            // One Firebase user lookup per owned API request; never trust client role headers.
            return Boolean.TRUE.equals(firebaseAuth.get().getUser(uid).getCustomClaims().get("admin"));
        } catch (FirebaseAuthException | IllegalStateException exception) {
            // A failed role check cannot fall back to a caller-supplied authority or grant access.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ADMIN_ROLE_LOOKUP_UNAVAILABLE",
                    "관리자 권한을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
