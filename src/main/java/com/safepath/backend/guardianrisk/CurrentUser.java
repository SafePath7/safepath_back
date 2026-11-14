package com.safepath.backend.guardianrisk;

import java.util.Objects;
import java.util.function.BooleanSupplier;

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
        // The admin claim costs a Firebase network call, so it is read only when a
        // request actually depends on it (and at most once per request).
        return new Actor(id, () -> roles.isAdmin(uid));
    }

    public static final class Actor {
        private final long id;
        private final BooleanSupplier adminLookup;
        private Boolean admin;

        public Actor(long id, boolean admin) {
            this.id = id;
            this.admin = admin;
            this.adminLookup = null;
        }

        Actor(long id, BooleanSupplier adminLookup) {
            this.id = id;
            this.adminLookup = Objects.requireNonNull(adminLookup);
        }

        public long id() { return id; }

        public boolean admin() {
            if (admin == null) admin = adminLookup.getAsBoolean();
            return admin;
        }

        public void requireAdmin() {
            if (!admin()) throw ApiException.forbidden();
        }

        public void requireOwner(long ownerId) {
            if (id != ownerId) throw ApiException.forbidden();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Actor actor && id == actor.id && admin() == actor.admin();
        }

        @Override
        public int hashCode() { return Long.hashCode(id); }

        @Override
        public String toString() { return "Actor[id=" + id + "]"; }
    }
}
