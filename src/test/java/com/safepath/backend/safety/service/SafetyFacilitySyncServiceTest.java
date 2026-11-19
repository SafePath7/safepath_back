package com.safepath.backend.safety.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.safepath.backend.safety.dto.SafetyFacilityImportItem;
import com.safepath.backend.safety.entity.SafetyFacility;
import com.safepath.backend.safety.repository.SafetyFacilityRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SafetyFacilitySyncServiceTest {
    private final SafetyFacilityRepository facilities = mock(SafetyFacilityRepository.class);
    private final SafetyFacilitySyncService service = new SafetyFacilitySyncService(facilities);

    @Test
    void reportsWhetherFacilitiesExistForSource() {
        when(facilities.existsBySource("CCTV")).thenReturn(true);

        assertTrue(service.hasFacilities("CCTV"));

        verify(facilities).existsBySource("CCTV");
    }

    @Test
    void createsFacilityWhenSourceAndExternalIdDoNotExist() {
        SafetyFacilityImportItem item = item();
        when(facilities.findBySourceAndExternalId("CCTV", "CCTV-100")).thenReturn(Optional.empty());

        service.sync(item);

        ArgumentCaptor<SafetyFacility> facility = ArgumentCaptor.forClass(SafetyFacility.class);
        verify(facilities).save(facility.capture());
        assertEquals("CCTV", facility.getValue().getSource());
        assertEquals("CCTV-100", facility.getValue().getExternalId());
    }

    @Test
    void updatesFacilityWhenSourceAndExternalIdExist() {
        SafetyFacility facility = SafetyFacility.create(
                "CCTV", "CCTV-100", 37.0, 126.0, "Old address", "CCTV");
        when(facilities.findBySourceAndExternalId("CCTV", "CCTV-100")).thenReturn(Optional.of(facility));

        service.sync(item());

        assertEquals(37.5665, facility.getLatitude());
        assertEquals("Seoul", facility.getAddress());
        verify(facilities, never()).save(any());
    }

    @Test
    void returnsCreatedAndUpdatedCountsForBatch() {
        SafetyFacility existing = SafetyFacility.create(
                "CCTV", "CCTV-100", 37.0, 126.0, "Old address", "CCTV");
        SafetyFacilityImportItem existingItem = new SafetyFacilityImportItem(
                "CCTV", "CCTV-100", 37.5670, 126.9790, "New address", "CCTV");
        SafetyFacilityImportItem newItem = new SafetyFacilityImportItem(
                "CCTV", "CCTV-101", 37.5665, 126.9780, "Seoul", "CCTV");
        when(facilities.findBySourceAndExternalIdIn("CCTV", List.of("CCTV-101", "CCTV-100")))
                .thenReturn(List.of(existing));

        SafetyFacilitySyncService.SyncResult result = service.syncAll(List.of(newItem, existingItem));

        assertEquals(1, result.createdCount());
        assertEquals(1, result.updatedCount());
        assertEquals("New address", existing.getAddress());
        verify(facilities).saveAll(any());
    }

    private SafetyFacilityImportItem item() {
        return new SafetyFacilityImportItem("CCTV", "CCTV-100", 37.5665, 126.9780, "Seoul", "CCTV");
    }
}
