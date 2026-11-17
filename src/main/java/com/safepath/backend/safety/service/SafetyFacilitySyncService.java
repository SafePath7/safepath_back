package com.safepath.backend.safety.service;

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
        facilities.findBySourceAndExternalId(item.source(), item.externalId())
                .ifPresentOrElse(
                        facility -> facility.update(
                                item.latitude(), item.longitude(), item.address(), item.facilityType()),
                        () -> facilities.save(SafetyFacility.create(
                                item.source(), item.externalId(), item.latitude(), item.longitude(),
                                item.address(), item.facilityType())));
    }
}
