package com.safepath.backend.guardian.entity;

import java.time.LocalDateTime;

import com.safepath.backend.common.util.UtcTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** New rows come from the repository's conflict-safe insert, never from a constructor. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "guardian_registrations")
public class GuardianRegistration {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(name = "guardian_id", nullable = false, updatable = false)
    private Long guardianId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RegistrationStatus status;

    // Updatable: a re-request after REJECTED/REVOKED restarts the request time.
    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;
    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Convert(converter = FlagConverter.class)
    @Column(name = "can_view_location", nullable = false)
    private boolean canViewLocation;
    @Convert(converter = FlagConverter.class)
    @Column(name = "can_view_status", nullable = false)
    private boolean canViewStatus;
    @Convert(converter = FlagConverter.class)
    @Column(name = "can_view_personal_info", nullable = false)
    private boolean canViewPersonalInfo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void changeStatus(RegistrationStatus status) {
        this.status = status;
        this.updatedAt = UtcTime.now();
        if (status == RegistrationStatus.ACCEPTED) {
            this.acceptedAt = this.updatedAt;
            // On acceptance the guardian starts with every view permission on;
            // the user can still turn any of them off through the permissions API.
            this.canViewLocation = true;
            this.canViewStatus = true;
            this.canViewPersonalInfo = true;
        }
    }

    /**
     * Turns an ended (REJECTED/REVOKED) registration back into a fresh PENDING request.
     * The earlier acceptance and its view permissions are cleared, so nothing is visible
     * to the guardian until they accept again and the user grants permissions again.
     */
    public void reopenAsPending() {
        LocalDateTime now = UtcTime.now();
        this.status = RegistrationStatus.PENDING;
        this.requestedAt = now;
        this.acceptedAt = null;
        this.canViewLocation = false;
        this.canViewStatus = false;
        this.canViewPersonalInfo = false;
        this.updatedAt = now;
    }

    public void changePermissions(boolean location, boolean status, boolean personalInfo) {
        this.canViewLocation = location;
        this.canViewStatus = status;
        this.canViewPersonalInfo = personalInfo;
        this.updatedAt = UtcTime.now();
    }

    /** A permission counts only while the registration is ACCEPTED. */
    public boolean allows(PermissionType type) {
        return status == RegistrationStatus.ACCEPTED && switch (type) {
            case LOCATION -> canViewLocation;
            case STATUS -> canViewStatus;
            case PERSONAL_INFO -> canViewPersonalInfo;
        };
    }
}
