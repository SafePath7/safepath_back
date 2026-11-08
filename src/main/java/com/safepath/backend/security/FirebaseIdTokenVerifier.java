package com.safepath.backend.security;

/** Implemented by the common authentication module; no token verification is implemented here. */
public interface FirebaseIdTokenVerifier {
    VerifiedIdentity verify(String idToken);

    record VerifiedIdentity(String uid, boolean admin) {
        public VerifiedIdentity {
            if (uid == null || uid.isBlank() || uid.length() > 128) {
                throw new IllegalArgumentException("A verified Firebase UID is required");
            }
        }
    }
}
