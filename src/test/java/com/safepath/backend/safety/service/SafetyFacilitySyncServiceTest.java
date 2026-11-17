package com.safepath.backend.safety.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.List;

import com.safepath.backend.safety.dto.SafetyFacilityImportItem;
import com.safepath.backend.safety.entity.SafetyFacility;
import com.safepath.backend.safety.repository.SafetyFacilityRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SafetyFacilitySyncServiceTest {
    private final SafetyFacilityRepository facilities = mock(SafetyFacilityRepository.class);
    private final SafetyFacilitySyncService service = new SafetyFacilitySyncService(facilities);

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
                "SECURITY_LIGHT", "LIGHT-100", 37.0, 126.0, "Old address", "SECURITY_LIGHT");
        SafetyFacilityImportItem newItem = item();
        SafetyFacilityImportItem existingItem = new SafetyFacilityImportItem(
                "SECURITY_LIGHT", "LIGHT-100", 37.5670, 126.9790, "New address", "SECURITY_LIGHT");
        when(facilities.findBySourceAndExternalId("CCTV", "CCTV-100")).thenReturn(Optional.empty());
        when(facilities.findBySourceAndExternalId("SECURITY_LIGHT", "LIGHT-100"))
                .thenReturn(Optional.of(existing));

        SafetyFacilitySyncService.SyncResult result = service.syncAll(List.of(newItem, existingItem));

        assertEquals(1, result.createdCount());
        assertEquals(1, result.updatedCount());
        assertEquals("New address", existing.getAddress());
    }

    private SafetyFacilityImportItem item() {
        return new SafetyFacilityImportItem("CCTV", "CCTV-100", 37.5665, 126.9780, "Seoul", "CCTV");
    }
}
