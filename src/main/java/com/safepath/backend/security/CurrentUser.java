package com.safepath.backend.security;

import com.safepath.backend.common.ApiException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    private final JdbcTemplate jdbc;

    public CurrentUser(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Actor resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw ApiException.forbidden();
        }
        long id = jdbc.query("SELECT id FROM users WHERE firebase_uid = ?",
                (rs, row) -> rs.getLong(1), authentication.getName()).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("회원"));
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
        return new Actor(id, admin);
    }

    public record Actor(long id, boolean admin) {
        public void requireAdmin() { if (!admin) throw ApiException.forbidden(); }
        public void requireOwner(long ownerId) { if (id != ownerId) throw ApiException.forbidden(); }
    }
}
