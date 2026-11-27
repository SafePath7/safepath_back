package com.safepath.backend.risk.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.safepath.backend.risk.entity.RiskEventMedia;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskEventMediaRepository extends JpaRepository<RiskEventMedia, Long> {
    List<RiskEventMedia> findByEventIdOrderById(Long eventId);

    // Matching on the event as well keeps a media id from being used through another event.
    Optional<RiskEventMedia> findByEventIdAndId(Long eventId, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM RiskEventMedia m WHERE m.eventId = :eventId AND m.id = :id")
    Optional<RiskEventMedia> findForUpdateByEventIdAndId(@Param("eventId") Long eventId, @Param("id") Long id);

    /** Rows whose file has outlived the retention period; V1 rows may lack uploaded_at. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM RiskEventMedia m WHERE m.storageUrl IS NOT NULL"
            + " AND COALESCE(m.uploadedAt, m.createdAt) < :cutoff ORDER BY m.id")
    List<RiskEventMedia> findExpiredForUpdate(@Param("cutoff") LocalDateTime cutoff, Pageable page);
}
