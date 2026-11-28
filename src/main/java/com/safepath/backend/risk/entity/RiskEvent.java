package com.safepath.backend.risk.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.safepath.backend.common.util.UtcTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "risk_events")
public class RiskEvent {
    private static final String INITIAL_STATUS = "PENDING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", updatable = false)
    private UUID eventKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private RiskSessionSnapshot session;

    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;
    @Column(nullable = false, length = 500)
    private String description;
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public RiskEvent(RiskSessionSnapshot session, String eventType, String description, LocalDateTime detectedAt) {
        this.session = session;
        this.eventType = eventType;
        this.description = description;
        this.status = INITIAL_STATUS;
        this.detectedAt = detectedAt;
        this.createdAt = UtcTime.now();
        this.updatedAt = this.createdAt;
    }

    public void changeStatus(String status) {
        this.status = status;
        this.updatedAt = UtcTime.now();
    }

    public UUID getSessionId() {
        return session.getId();
    }

    /** The member the event happened to. */
    public Long getUserId() {
        return session.getUserId();
    }
}
