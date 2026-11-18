package com.safepath.backend.safety.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.safepath.backend.safety.entity.SafetyFacility;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SafetyFacilityRepository extends JpaRepository<SafetyFacility, Long> {
    boolean existsBySource(String source);

    Optional<SafetyFacility> findBySourceAndExternalId(String source, String externalId);

    List<SafetyFacility> findBySourceAndExternalIdIn(String source, Collection<String> externalIds);

    List<SafetyFacility> findByLatitudeBetweenAndLongitudeBetweenOrderByIdAsc(
            double southWestLat, double northEastLat, double southWestLng, double northEastLng);

    List<SafetyFacility> findByLatitudeBetweenAndLongitudeBetweenOrderByIdAsc(
            double southWestLat, double northEastLat, double southWestLng, double northEastLng, Pageable page);

    long countByLatitudeBetweenAndLongitudeBetween(
            double southWestLat, double northEastLat, double southWestLng, double northEastLng);

    @Query(value = """
            SELECT AVG(latitude) AS latitude, AVG(longitude) AS longitude, COUNT(*) AS count
            FROM safety_facilities
            WHERE latitude BETWEEN :southWestLat AND :northEastLat
              AND longitude BETWEEN :southWestLng AND :northEastLng
            GROUP BY FLOOR(latitude / :gridSize), FLOOR(longitude / :gridSize)
            ORDER BY latitude, longitude
            """, nativeQuery = true)
    List<SafetyFacilityClusterProjection> findClustersInBounds(
            @Param("southWestLat") double southWestLat,
            @Param("northEastLat") double northEastLat,
            @Param("southWestLng") double southWestLng,
            @Param("northEastLng") double northEastLng,
            @Param("gridSize") double gridSize);
}
