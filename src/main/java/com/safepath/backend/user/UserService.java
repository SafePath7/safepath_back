package com.safepath.backend.user;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final JdbcTemplate jdbc;

    public UserService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> findUserIdByFirebaseUid(String uid) {
        return jdbc.query("SELECT id FROM users WHERE firebase_uid = ?",
                (rs, rowNum) -> rs.getLong("id"), uid).stream().findFirst();
    }
}
