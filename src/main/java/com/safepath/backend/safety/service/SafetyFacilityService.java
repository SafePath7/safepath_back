package com.safepath.backend.safety.service;

import java.util.List;

import com.safepath.backend.safety.dto.SafetyFacilityResponse;
import com.safepath.backend.safety.repository.SafetyFacilityRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class SafetyFacilityService {
    private static final int LOW_ZOOM_LIMIT = 100;
    private static final int MID_ZOOM_LIMIT = 300;
    private static final int MID_ZOOM_END = 6;
    private static final int CLOSE_ZOOM_END = 3;

    private final SafetyFacilityRepository facilities;

    public SafetyFacilityService(SafetyFacilityRepository facilities) {
        this.facilities = facilities;
    }

    @Transactional(readOnly = true)
    public List<SafetyFacilityResponse> findInBounds(int zoom, double southWestLat, double southWestLng,
            double northEastLat, double northEastLng) {
        if (southWestLat > northEastLat || southWestLng > northEastLng) {
            throw new ResponseStatusException(BAD_REQUEST, "Map bounds must be ordered south-west to north-east");
        }

        List<SafetyFacilityResponse> result = (zoom <= CLOSE_ZOOM_END
                ? facilities.findByLatitudeBetweenAndLongitudeBetweenOrderByIdAsc(
                        southWestLat, northEastLat, southWestLng, northEastLng)
                : facilities.findByLatitudeBetweenAndLongitudeBetweenOrderByIdAsc(
                        southWestLat, northEastLat, southWestLng, northEastLng,
                        PageRequest.of(0, zoom <= MID_ZOOM_END ? MID_ZOOM_LIMIT : LOW_ZOOM_LIMIT)))
                .stream().map(SafetyFacilityResponse::from).toList();
        return result;
    }
}
