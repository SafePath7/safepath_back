package com.safepath.backend.guardian;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import com.safepath.backend.guardian.GuardianModels.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class GuardianRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Registration> MAPPER = (rs, row) -> new Registration(
            rs.getLong("id"), rs.getLong("user_id"), rs.getLong("guardian_id"),
            Status.valueOf(rs.getString("status")), time(rs, "requested_at"),
            time(rs, "accepted_at"), rs.getShort("can_view_location") == 1,
            rs.getShort("can_view_status") == 1, rs.getShort("can_view_personal_info") == 1,
            time(rs, "created_at"), time(rs, "updated_at"));

    public GuardianRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Instant time(ResultSet rs, String field) throws SQLException {
        var value = rs.getObject(field, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    public boolean userExists(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE id = ?)", Boolean.class, id));
    }

    public Optional<Registration> request(long userId, long guardianId) {
        return jdbc.query("""
                INSERT INTO guardian_registrations (user_id, guardian_id, status, requested_at, created_at, updated_at)
                VALUES (?, ?, 'PENDING', CURRENT_TIMESTAMP AT TIME ZONE 'UTC',
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                ON CONFLICT (user_id, guardian_id) DO NOTHING
                RETURNING *
                """, MAPPER, userId, guardianId).stream().findFirst();
    }

    public Optional<Registration> find(long id, boolean lock) {
        return jdbc.query("SELECT * FROM guardian_registrations WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                MAPPER, id).stream().findFirst();
    }

    public void setStatus(long id, Status status) {
        jdbc.update("""
                UPDATE guardian_registrations SET status = ?,
                    accepted_at = CASE WHEN ? = 'ACCEPTED' THEN CURRENT_TIMESTAMP AT TIME ZONE 'UTC' ELSE accepted_at END,
                    updated_at = CURRENT_TIMESTAMP AT TIME ZONE 'UTC'
                WHERE id = ?
                """, status.name(), status.name(), id);
    }

    public void setPermissions(long id, PermissionRequest permissions) {
        jdbc.update("""
                UPDATE guardian_registrations
                SET can_view_location = ?, can_view_status = ?, can_view_personal_info = ?,
                    updated_at = CURRENT_TIMESTAMP AT TIME ZONE 'UTC'
                WHERE id = ?
                """, permissions.canViewLocation() ? 1 : 0, permissions.canViewStatus() ? 1 : 0,
                permissions.canViewPersonalInfo() ? 1 : 0, id);
    }

    public boolean allows(long ownerId, long guardianId, PermissionType type) {
        String column = switch (type) {
            case LOCATION -> "can_view_location";
            case STATUS -> "can_view_status";
            case PERSONAL_INFO -> "can_view_personal_info";
        };
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM guardian_registrations
                WHERE user_id = ? AND guardian_id = ? AND status = 'ACCEPTED' AND
                """ + column + " = 1)", Boolean.class, ownerId, guardianId));
    }
}
