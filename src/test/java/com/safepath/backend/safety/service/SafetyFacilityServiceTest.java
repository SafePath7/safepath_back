package com.safepath.backend.safety.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.safepath.backend.safety.entity.SafetyFacility;
import com.safepath.backend.safety.repository.SafetyFacilityClusterProjection;
import com.safepath.backend.safety.repository.SafetyFacilityRepository;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SafetyFacilityServiceTest {
    private final SafetyFacilityRepository facilities = mock(SafetyFacilityRepository.class);
    private final SafetyFacilityService service = new SafetyFacilityService(facilities);

    @Test
    void returnsClustersAtNonDetailedKakaoMapLevels() {
        SafetyFacilityClusterProjection cluster = cluster(37.5, 127.0, 12L);
        when(facilities.countByLatitudeBetweenAndLongitudeBetween(37.0, 38.0, 126.0, 127.0)).thenReturn(1L);
        when(facilities.findClustersInBounds(eq(37.0), eq(38.0), eq(126.0), eq(127.0), eq(0.08)))
                .thenReturn(List.of(cluster));

        var distantResponse = service.findInBounds(7, 37.0, 126.0, 38.0, 127.0);

        assertEquals("CLUSTER", distantResponse.mode());
        assertEquals(1, distantResponse.totalCount());
        assertEquals(0, distantResponse.facilities().size());
        assertEquals(1, distantResponse.clusters().size());
        assertEquals(12L, distantResponse.clusters().getFirst().count());
    }

    @Test
    void returnsAllFacilitiesAtCloseKakaoMapLevel() {
        SafetyFacility first = facility(1L);
        SafetyFacility second = facility(2L);
        when(facilities.findByLatitudeBetweenAndLongitudeBetweenOrderByIdAsc(37.0, 38.0, 126.0, 127.0))
                .thenReturn(List.of(first, second));
        when(facilities.countByLatitudeBetweenAndLongitudeBetween(37.0, 38.0, 126.0, 127.0)).thenReturn(2L);

        var response = service.findInBounds(3, 37.0, 126.0, 38.0, 127.0);

        assertEquals("MARKER", response.mode());
        assertEquals(2, response.facilities().size());
        assertEquals(0, response.clusters().size());
        verify(facilities).findByLatitudeBetweenAndLongitudeBetweenOrderByIdAsc(37.0, 38.0, 126.0, 127.0);
    }

    @Test
    void rejectsReversedBounds() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.findInBounds(3, 38.0, 126.0, 37.0, 127.0));
        assertEquals(400, error.getStatusCode().value());
    }

    private SafetyFacility facility(long id) {
        SafetyFacility facility = mock(SafetyFacility.class);
        when(facility.getId()).thenReturn(id);
        when(facility.getLatitude()).thenReturn(37.5);
        when(facility.getLongitude()).thenReturn(127.0);
        when(facility.getAddress()).thenReturn("Seoul");
        when(facility.getFacilityType()).thenReturn("SHELTER");
        return facility;
    }

    private SafetyFacilityClusterProjection cluster(double latitude, double longitude, long count) {
        SafetyFacilityClusterProjection cluster = mock(SafetyFacilityClusterProjection.class);
        when(cluster.getLatitude()).thenReturn(latitude);
        when(cluster.getLongitude()).thenReturn(longitude);
        when(cluster.getCount()).thenReturn(count);
        return cluster;
    }
}
