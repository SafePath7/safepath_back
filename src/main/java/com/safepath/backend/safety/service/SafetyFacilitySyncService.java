package com.safepath.backend.safety.service;

import java.util.List;
import java.util.Optional;

import com.safepath.backend.safety.dto.SafetyFacilityImportItem;
import com.safepath.backend.safety.entity.SafetyFacility;
import com.safepath.backend.safety.repository.SafetyFacilityRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SafetyFacilitySyncService {
    private final SafetyFacilityRepository facilities;

    public SafetyFacilitySyncService(SafetyFacilityRepository facilities) {
        this.facilities = facilities;
    }

    @Transactional
    public void sync(SafetyFacilityImportItem item) {
        syncItem(item);
    }

    @Transactional
    public SyncResult syncAll(List<SafetyFacilityImportItem> items) {
        int createdCount = 0;
        int updatedCount = 0;

        for (SafetyFacilityImportItem item : items) {
            if (syncItem(item)) {
                createdCount++;
            } else {
                updatedCount++;
            }
        }

        return new SyncResult(createdCount, updatedCount);
    }

    private boolean syncItem(SafetyFacilityImportItem item) {
        Optional<SafetyFacility> facility = facilities.findBySourceAndExternalId(item.source(), item.externalId());
        if (facility.isPresent()) {
            facility.get().update(item.latitude(), item.longitude(), item.address(), item.facilityType());
            return false;
        }

        facilities.save(SafetyFacility.create(
                item.source(), item.externalId(), item.latitude(), item.longitude(),
                item.address(), item.facilityType()));
        return true;
    }

    public record SyncResult(int createdCount, int updatedCount) {
    }
}
