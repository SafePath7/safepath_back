package com.safepath.backend.risk.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.safepath.backend.risk.entity.RiskEvent;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskEventRepository extends JpaRepository<RiskEvent, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM RiskEvent e WHERE e.id = :id")
    Optional<RiskEvent> findForUpdateById(@Param("id") Long id);

    // The session is fetched with the event because every reader needs its owner.
    @Query("SELECT e FROM RiskEvent e JOIN FETCH e.session WHERE e.id = :id")
    Optional<RiskEvent> findWithSessionById(@Param("id") Long id);

    /** The member's events, most recently detected first. */
    @Query("""
            SELECT e FROM RiskEvent e JOIN FETCH e.session s
            WHERE s.userId = :userId
            ORDER BY e.detectedAt DESC, e.id DESC
            """)
    List<RiskEvent> findByUserId(@Param("userId") Long userId, Pageable page);

    @Query("""
            SELECT e FROM RiskEvent e JOIN FETCH e.session s
            WHERE s.userId = :userId AND s.id = :sessionId
            ORDER BY e.detectedAt DESC, e.id DESC
            """)
    List<RiskEvent> findByUserIdAndSessionId(@Param("userId") Long userId,
            @Param("sessionId") UUID sessionId, Pageable page);
}
