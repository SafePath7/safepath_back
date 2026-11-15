package com.safepath.backend.risk.entity;

import java.time.LocalDateTime;

import com.safepath.backend.common.util.UtcTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "risk_event_medias")
public class RiskEventMedia {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;
    @Column(name = "media_type", nullable = false, length = 20)
    private String mediaType;
    @Column(name = "file_name", length = 255)
    private String fileName;
    /** Internal storage location (local key or s3:// URI); never sent to clients. */
    @Column(name = "storage_url", length = 2048)
    private String storageUrl;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public RiskEventMedia(Long eventId, String mediaType, String fileName, String storageUrl) {
        this.eventId = eventId;
        this.mediaType = mediaType;
        this.fileName = fileName;
        this.storageUrl = storageUrl;
        this.uploadedAt = UtcTime.now();
        this.createdAt = this.uploadedAt;
        this.updatedAt = this.uploadedAt;
    }
}
