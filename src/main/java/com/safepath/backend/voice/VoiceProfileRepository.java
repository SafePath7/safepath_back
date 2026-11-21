package com.safepath.backend.voice;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoiceProfileRepository extends JpaRepository<VoiceProfile, Long> {
    @Query(value = "SELECT id FROM users WHERE id = :userId FOR UPDATE", nativeQuery = true)
    Optional<Long> lockOwner(@Param("userId") long userId);

    boolean existsByAudioFileUrlOrUrgentAudioFileUrl(String audioFileUrl, String urgentAudioFileUrl);
}
