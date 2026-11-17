package com.safepath.backend.safety.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.safepath.backend.safety.entity.SafetyFacility;
import com.safepath.backend.safety.repository.SafetyFacilityRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

class SafetyFacilityServiceTest {
    private final SafetyFacilityRepository facilities = mock(SafetyFacilityRepository.class);
    private final SafetyFacilityService service = new SafetyFacilityService(facilities);

    @Test
    void limitsResultsAtDistantAndMidKakaoMapLevels() {
        SafetyFacility facility = facility(1L);
        when(facilities.findByLatitudeBetweenAndLongitudeBetweenOrderByIdAsc(
                eq(37.0), eq(38.0), eq(126.0), eq(127.0), any(Pageable.class)))
                .thenReturn(List.of(facility));

        service.findInBounds(7, 37.0, 126.0, 38.0, 127.0);
        service.findInBounds(4, 37.0, 126.0, 38.0, 127.0);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(facilities, times(2)).findByLatitudeBetweenAndLongitudeBetweenOrderByIdAsc(
                eq(37.0), eq(38.0), eq(126.0), eq(127.0), page.capture());
        assertEquals(100, page.getAllValues().get(0).getPageSize());
        assertEquals(300, page.getAllValues().get(1).getPageSize());
    }

    @Test
    void returnsAllFacilitiesAtCloseKakaoMapLevel() {
        SafetyFacility first = facility(1L);
        SafetyFacility second = facility(2L);
        when(facilities.findByLatitudeBetweenAndLongitudeBetweenOrderByIdAsc(37.0, 38.0, 126.0, 127.0))
                .thenReturn(List.of(first, second));

        assertEquals(2, service.findInBounds(3, 37.0, 126.0, 38.0, 127.0).size());
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
}
