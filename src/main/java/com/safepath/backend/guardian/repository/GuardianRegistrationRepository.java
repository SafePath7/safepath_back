package com.safepath.backend.guardian.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.safepath.backend.guardian.entity.GuardianRegistration;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuardianRegistrationRepository extends JpaRepository<GuardianRegistration, Long> {
    Optional<GuardianRegistration> findByUserIdAndGuardianId(Long userId, Long guardianId);

    /** Locks the pair's row so two concurrent re-requests cannot both reopen it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM GuardianRegistration r WHERE r.userId = :userId AND r.guardianId = :guardianId")
    Optional<GuardianRegistration> findForUpdateByUserIdAndGuardianId(@Param("userId") Long userId,
            @Param("guardianId") Long guardianId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM GuardianRegistration r WHERE r.id = :id")
    Optional<GuardianRegistration> findForUpdateById(@Param("id") Long id);

    List<GuardianRegistration> findByUserIdOrderByIdDesc(Long userId);

    List<GuardianRegistration> findByGuardianIdOrderByIdDesc(Long guardianId);

    @Query("SELECT r FROM GuardianRegistration r WHERE r.userId = :memberId OR r.guardianId = :memberId ORDER BY r.id DESC")
    List<GuardianRegistration> findByMemberIdOrderByIdDesc(@Param("memberId") Long memberId);

    /**
     * A native insert keeps the atomic pair check of PostgreSQL: a concurrent request
     * for the same pair inserts nothing instead of failing the transaction.
     */
    @Modifying
    @Query(value = """
            INSERT INTO guardian_registrations (user_id, guardian_id, status, requested_at, created_at, updated_at)
            VALUES (:userId, :guardianId, 'PENDING', :now, :now, :now)
            ON CONFLICT (user_id, guardian_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("guardianId") Long guardianId,
            @Param("now") LocalDateTime now);

    // The users table belongs to the account module, so it is read with plain SQL here.
    @Query(value = "SELECT EXISTS (SELECT 1 FROM users WHERE id = :id)", nativeQuery = true)
    boolean memberExists(@Param("id") Long id);

    @Query(value = "SELECT id AS id, name AS name FROM users WHERE id IN (:ids)", nativeQuery = true)
    List<MemberName> findMemberNames(@Param("ids") Collection<Long> ids);

    interface MemberName {
        Long getId();
        String getName();
    }
}
