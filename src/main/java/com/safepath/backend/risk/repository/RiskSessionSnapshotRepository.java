package com.safepath.backend.risk.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import com.safepath.backend.risk.entity.RiskSessionSnapshot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskSessionSnapshotRepository extends JpaRepository<RiskSessionSnapshot, UUID> {
    @Modifying
    @Query("""
            UPDATE RiskSessionSnapshot s
            SET s.status = :status, s.endedAt = COALESCE(s.endedAt, :endedAt), s.updatedAt = :endedAt
            WHERE s.id = :id AND s.userId = :userId AND (s.endedAt IS NULL OR s.status <> :status)
            """)
    int markEnded(@Param("id") UUID id, @Param("userId") long userId,
            @Param("status") String status, @Param("endedAt") LocalDateTime endedAt);

    /** ON CONFLICT never replaces a row another request already inserted. */
    @Modifying
    @Query(value = """
            INSERT INTO safe_return_sessions (id, user_id, status, started_at, created_at, updated_at)
            VALUES (:id, :userId, 'ACTIVE', :startedAt, :now, :now)
            ON CONFLICT (id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userId") Long userId,
            @Param("startedAt") LocalDateTime startedAt, @Param("now") LocalDateTime now);
}
