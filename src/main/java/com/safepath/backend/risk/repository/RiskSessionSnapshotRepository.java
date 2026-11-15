package com.safepath.backend.risk.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import com.safepath.backend.risk.entity.RiskSessionSnapshot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskSessionSnapshotRepository extends JpaRepository<RiskSessionSnapshot, UUID> {
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
