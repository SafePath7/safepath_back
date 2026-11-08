package com.safepath.backend.risk;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.safepath.backend.risk.RiskModels.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RiskRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Location> LOCATION = (rs, row) -> new Location(rs.getLong("id"),
            rs.getObject("session_id", UUID.class), rs.getDouble("latitude"), rs.getDouble("longitude"),
            time(rs, "recorded_at"), time(rs, "created_at"));
    private static final RowMapper<RiskEvent> EVENT = (rs, row) -> new RiskEvent(rs.getLong("id"),
            rs.getObject("session_id", UUID.class), rs.getString("event_type"), rs.getString("description"),
            rs.getString("status"), time(rs, "detected_at"), time(rs, "created_at"), time(rs, "updated_at"));
    private static final RowMapper<Media> MEDIA = (rs, row) -> new Media(rs.getLong("id"), rs.getLong("event_id"),
            rs.getString("media_type"), rs.getString("file_name"), rs.getString("storage_url"),
            time(rs, "uploaded_at"), time(rs, "created_at"), time(rs, "updated_at"));

    public RiskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean canGuardianViewLocation(long ownerId, long guardianId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM guardian_registrations
                WHERE user_id = ? AND guardian_id = ? AND status = 'ACCEPTED' AND can_view_location = 1)
                """, Boolean.class, ownerId, guardianId));
    }

    private static Instant time(ResultSet rs, String field) throws SQLException {
        LocalDateTime value = rs.getObject(field, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    public Optional<Long> sessionOwner(UUID sessionId) {
        return jdbc.query("SELECT user_id FROM safe_return_sessions WHERE id = ?",
                (rs, row) -> rs.getLong(1), sessionId).stream().findFirst();
    }

    public Location insertLocation(LocationRequest request) {
        return jdbc.queryForObject("""
                INSERT INTO location_data (session_id, latitude, longitude, recorded_at, created_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP AT TIME ZONE 'UTC') RETURNING *
                """, LOCATION, request.sessionId(), request.latitude(), request.longitude(),
                LocalDateTime.ofInstant(request.recordedAt(), ZoneOffset.UTC));
    }

    public Optional<Location> latestLocation(UUID sessionId) {
        return jdbc.query("""
                SELECT * FROM location_data WHERE session_id = ?
                ORDER BY recorded_at DESC, id DESC LIMIT 1
                """, LOCATION, sessionId).stream().findFirst();
    }

    public RiskEvent insertEvent(EventRequest request) {
        return jdbc.queryForObject("""
                INSERT INTO risk_events (session_id, event_type, description, status, detected_at, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', ?, CURRENT_TIMESTAMP AT TIME ZONE 'UTC',
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC') RETURNING *
                """, EVENT, request.sessionId(), request.eventType(), request.description(),
                LocalDateTime.ofInstant(request.detectedAt(), ZoneOffset.UTC));
    }

    public Optional<RiskEvent> event(long eventId, boolean lock) {
        return jdbc.query("SELECT * FROM risk_events WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                EVENT, eventId).stream().findFirst();
    }

    public RiskEvent updateStatus(long eventId, String status) {
        return jdbc.queryForObject("""
                UPDATE risk_events SET status = ?, updated_at = CURRENT_TIMESTAMP AT TIME ZONE 'UTC'
                WHERE id = ? RETURNING *
                """, EVENT, status, eventId);
    }

    public Media insertMedia(long eventId, String mediaType, String fileName, String key) {
        return jdbc.queryForObject("""
                INSERT INTO risk_event_medias (event_id, media_type, file_name, storage_url, uploaded_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', CURRENT_TIMESTAMP AT TIME ZONE 'UTC',
                        CURRENT_TIMESTAMP AT TIME ZONE 'UTC') RETURNING *
                """, MEDIA, eventId, mediaType, fileName, key);
    }

    public List<Media> medias(long eventId) {
        return jdbc.query("SELECT * FROM risk_event_medias WHERE event_id = ? ORDER BY id", MEDIA, eventId);
    }

    public Optional<Media> media(long eventId, long mediaId) {
        return jdbc.query("SELECT * FROM risk_event_medias WHERE event_id = ? AND id = ? FOR UPDATE",
                MEDIA, eventId, mediaId).stream().findFirst();
    }

    public void deleteMedia(long eventId, long mediaId) {
        jdbc.update("DELETE FROM risk_event_medias WHERE event_id = ? AND id = ?", eventId, mediaId);
    }
}
