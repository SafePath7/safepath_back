package com.safepath.backend.safety.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @Transactional(readOnly = true)
    public boolean hasFacilities(String source) {
        return facilities.existsBySource(source);
    }

    @Transactional
    public void sync(SafetyFacilityImportItem item) {
        syncItem(item);
    }

    @Transactional
    public SyncResult syncAll(List<SafetyFacilityImportItem> items) {
        int createdCount = 0;
        int updatedCount = 0;
        Map<String, List<SafetyFacilityImportItem>> itemsBySource = new HashMap<>();

        for (SafetyFacilityImportItem item : items) {
            itemsBySource.computeIfAbsent(item.source(), ignored -> new ArrayList<>()).add(item);
        }

        for (Map.Entry<String, List<SafetyFacilityImportItem>> entry : itemsBySource.entrySet()) {
            List<SafetyFacilityImportItem> sourceItems = entry.getValue();
            List<String> externalIds = sourceItems.stream().map(SafetyFacilityImportItem::externalId).toList();
            Map<String, SafetyFacility> existingByExternalId = new HashMap<>();
            for (SafetyFacility facility : facilities.findBySourceAndExternalIdIn(entry.getKey(), externalIds)) {
                existingByExternalId.put(facility.getExternalId(), facility);
            }

            List<SafetyFacility> facilitiesToCreate = new ArrayList<>();
            for (SafetyFacilityImportItem item : sourceItems) {
                SafetyFacility facility = existingByExternalId.get(item.externalId());
                if (facility == null) {
                    facility = SafetyFacility.create(
                            item.source(), item.externalId(), item.latitude(), item.longitude(),
                            item.address(), item.facilityType());
                    existingByExternalId.put(item.externalId(), facility);
                    facilitiesToCreate.add(facility);
                    createdCount++;
                } else {
                    facility.update(item.latitude(), item.longitude(), item.address(), item.facilityType());
                    updatedCount++;
                }
            }

            if (!facilitiesToCreate.isEmpty()) {
                facilities.saveAll(facilitiesToCreate);
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
