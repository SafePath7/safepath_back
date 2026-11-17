package com.safepath.backend.safety.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.safepath.backend.safety.entity.SafetyFacility;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SafetyFacilityRepository extends JpaRepository<SafetyFacility, Long> {
    boolean existsBySource(String source);

    Optional<SafetyFacility> findBySourceAndExternalId(String source, String externalId);

    List<SafetyFacility> findBySourceAndExternalIdIn(String source, Collection<String> externalIds);

    List<SafetyFacility> findByLatitudeBetweenAndLongitudeBetweenOrderByIdAsc(
            double southWestLat, double northEastLat, double southWestLng, double northEastLng);

    List<SafetyFacility> findByLatitudeBetweenAndLongitudeBetweenOrderByIdAsc(
            double southWestLat, double northEastLat, double southWestLng, double northEastLng, Pageable page);
}
