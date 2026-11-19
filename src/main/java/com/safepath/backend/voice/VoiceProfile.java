package com.safepath.backend.voice;

import com.safepath.backend.common.util.UtcTime;
import com.safepath.backend.voice.exception.VoiceProfileException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/** V1 mapping. TIMESTAMP values use UTC without a stored time zone. */
@Entity
@Table(name = "voice_profiles")
public class VoiceProfile implements Persistable<Long> {
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "speaker_embedding", nullable = false, columnDefinition = "real[]")
    private float[] speakerEmbedding;
    @Column(name = "audio_file_url", nullable = false, columnDefinition = "text")
    private String audioFileUrl;
    @Column(name = "baseline_volume", nullable = false)
    private double baselineVolume;
    @Column(name = "baseline_pitch", nullable = false)
    private double baselinePitch;
    @Column(name = "model_version", nullable = false, length = 50)
    private String modelVersion;
    @Column(name = "consented_at", nullable = false)
    private LocalDateTime consentedAt;
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    private boolean newProfile = true;

    protected VoiceProfile() {}

    public VoiceProfile(Long userId, float[] speakerEmbedding, String audioFileUrl,
            double baselineVolume, double baselinePitch, String modelVersion, LocalDateTime consentedAt) {
        if (userId == null || userId <= 0) throw VoiceProfileException.invalid("userId");
        this.userId = userId;
        replaceValues(speakerEmbedding, audioFileUrl, baselineVolume, baselinePitch, modelVersion, consentedAt);
        this.createdAt = UtcTime.now();
        this.updatedAt = createdAt;
    }

    public void update(float[] speakerEmbedding, String audioFileUrl, double baselineVolume,
            double baselinePitch, String modelVersion, LocalDateTime consentedAt) {
        replaceValues(speakerEmbedding, audioFileUrl, baselineVolume, baselinePitch, modelVersion, consentedAt);
        refreshUpdatedAt();
    }

    private void replaceValues(float[] embedding, String audioUrl, double volume, double pitch,
            String version, LocalDateTime consent) {
        if (embedding == null || embedding.length == 0) throw VoiceProfileException.invalid("speakerEmbedding");
        for (float value : embedding) {
            if (!Float.isFinite(value)) throw VoiceProfileException.invalid("speakerEmbedding");
        }
        if (audioUrl == null || audioUrl.isBlank()) throw VoiceProfileException.invalid("audioFileUrl");
        if (!Double.isFinite(volume)) throw VoiceProfileException.invalid("baselineVolume");
        if (!Double.isFinite(pitch)) throw VoiceProfileException.invalid("baselinePitch");
        if (version == null || version.isBlank() || version.codePointCount(0, version.length()) > 50) {
            throw VoiceProfileException.invalid("modelVersion");
        }
        if (consent == null) throw VoiceProfileException.invalid("consentedAt");
        // Validate everything before changing an existing managed entity.
        this.speakerEmbedding = embedding.clone();
        this.audioFileUrl = audioUrl;
        this.baselineVolume = volume;
        this.baselinePitch = pitch;
        this.modelVersion = version;
        this.consentedAt = consent;
    }

    @PreUpdate
    private void refreshUpdatedAt() { updatedAt = UtcTime.now(); }

    @PostLoad
    @PostPersist
    private void markPersisted() { newProfile = false; }

    /** Assigned user IDs must INSERT, not merge over a concurrent creator's row. */
    @Override public boolean isNew() { return newProfile; }
    @Override public Long getId() { return userId; }
    public Long getUserId() { return userId; }
    public float[] getSpeakerEmbedding() { return speakerEmbedding.clone(); }
    public String getAudioFileUrl() { return audioFileUrl; }
    public double getBaselineVolume() { return baselineVolume; }
    public double getBaselinePitch() { return baselinePitch; }
    public String getModelVersion() { return modelVersion; }
    public LocalDateTime getConsentedAt() { return consentedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
