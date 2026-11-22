package com.safepath.backend.risk.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The risk module's read view of safe_return_sessions. The session module keeps live
 * sessions in memory. The first risk event creates the row, and the session
 * lifecycle updates that existing row when the session ends.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "safe_return_sessions")
public class RiskSessionSnapshot {
    public static final String ENDED = "ENDED";
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;
    @Column(name = "ended_at")
    private LocalDateTime endedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
