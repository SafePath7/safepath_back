package com.safepath.backend.safety.service;

import java.util.List;

import com.safepath.backend.safety.dto.SafetyFacilityMapResponse;
import com.safepath.backend.safety.dto.SafetyFacilityResponse;
import com.safepath.backend.safety.dto.SafetyFacilityClusterResponse;
import com.safepath.backend.safety.entity.SafetyFacility;
import com.safepath.backend.safety.repository.SafetyFacilityClusterProjection;
import com.safepath.backend.safety.repository.SafetyFacilityRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class SafetyFacilityService {
    private static final int CLOSE_ZOOM_END = 3;
    private static final int MAX_KAKAO_MAP_LEVEL = 14;
    private static final double MAX_LEVEL_GRID_SIZE = 10.24;

    private final SafetyFacilityRepository facilities;

    public SafetyFacilityService(SafetyFacilityRepository facilities) {
        this.facilities = facilities;
    }

    @Transactional(readOnly = true)
    public SafetyFacilityMapResponse findInBounds(int zoom, double southWestLat, double southWestLng,
            double northEastLat, double northEastLng) {
        if (southWestLat > northEastLat || southWestLng > northEastLng) {
            throw new ResponseStatusException(BAD_REQUEST, "Map bounds must be ordered south-west to north-east");
        }

        long totalCount = facilities.countByLatitudeBetweenAndLongitudeBetween(
                southWestLat, northEastLat, southWestLng, northEastLng);
        if (zoom <= CLOSE_ZOOM_END) {
            List<SafetyFacilityResponse> result = facilities
                    .findByLatitudeBetweenAndLongitudeBetweenOrderByIdAsc(
                            southWestLat, northEastLat, southWestLng, northEastLng)
                    .stream().map(SafetyFacilityResponse::from).toList();
            return new SafetyFacilityMapResponse("MARKER", totalCount, List.of(), result);
        }

        List<SafetyFacilityClusterResponse> clusters = facilities.findClustersInBounds(
                southWestLat, northEastLat, southWestLng, northEastLng, gridSizeFor(zoom))
                .stream().map(this::toClusterResponse).toList();
        return new SafetyFacilityMapResponse("CLUSTER", totalCount, clusters, List.of());
    }

    private double gridSizeFor(int zoom) {
        return MAX_LEVEL_GRID_SIZE / Math.pow(2, MAX_KAKAO_MAP_LEVEL - zoom);
    }

    private SafetyFacilityClusterResponse toClusterResponse(SafetyFacilityClusterProjection cluster) {
        return new SafetyFacilityClusterResponse(cluster.getLatitude(), cluster.getLongitude(), cluster.getCount());
    }
}
