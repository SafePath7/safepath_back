package com.safepath.backend.guardianrisk;

import com.safepath.backend.user.UserService;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Adapts the account module's already authenticated Firebase UID for our APIs. */
@Component
public class CurrentUser {
    private final UserService users;
    private final FirebaseAdminRoleLookup roles;

    public CurrentUser(UserService users, FirebaseAdminRoleLookup roles) {
        this.users = users;
        this.roles = roles;
    }

    public Actor resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw ApiException.forbidden();
        }
        String uid = authentication.getName();
        if (uid == null || uid.isBlank()) throw ApiException.forbidden();
        long id = users.findUserIdByFirebaseUid(uid)
                .orElseThrow(() -> ApiException.notFound("회원"));
        return new Actor(id, roles.isAdmin(uid));
    }

    public record Actor(long id, boolean admin) {
        public void requireAdmin() {
            if (!admin) throw ApiException.forbidden();
        }

        public void requireOwner(long ownerId) {
            if (id != ownerId) throw ApiException.forbidden();
        }
    }
}
