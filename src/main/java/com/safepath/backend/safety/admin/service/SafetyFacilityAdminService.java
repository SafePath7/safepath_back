package com.safepath.backend.safety.admin.service;

import java.util.UUID;

import com.safepath.backend.common.auth.CurrentUser.Actor;
import com.safepath.backend.common.exception.ApiException;
import com.safepath.backend.safety.admin.dto.SafetyFacilityAdminListResponse;
import com.safepath.backend.safety.admin.dto.SafetyFacilityAdminRequest;
import com.safepath.backend.safety.admin.dto.SafetyFacilityAdminResponse;
import com.safepath.backend.safety.entity.SafetyFacility;
import com.safepath.backend.safety.repository.SafetyFacilityRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SafetyFacilityAdminService {
    private static final String ADMIN_SOURCE = "ADMIN";

    private final SafetyFacilityRepository facilities;

    public SafetyFacilityAdminService(SafetyFacilityRepository facilities) {
        this.facilities = facilities;
    }

    @Transactional(readOnly = true)
    public SafetyFacilityAdminListResponse list(Actor actor, int page, int size) {
        actor.requireAdmin();
        var found = facilities.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return new SafetyFacilityAdminListResponse(
                found.getContent().stream().map(SafetyFacilityAdminResponse::from).toList(),
                found.getTotalElements(), found.getNumber(), found.getSize());
    }

    @Transactional(readOnly = true)
    public SafetyFacilityAdminResponse get(Actor actor, long facilityId) {
        actor.requireAdmin();
        return SafetyFacilityAdminResponse.from(find(facilityId));
    }

    @Transactional
    public SafetyFacilityAdminResponse create(Actor actor, SafetyFacilityAdminRequest request) {
        actor.requireAdmin();
        SafetyFacility facility = SafetyFacility.create(
                ADMIN_SOURCE, UUID.randomUUID().toString(), request.latitude(), request.longitude(),
                request.address(), request.facilityType());
        return SafetyFacilityAdminResponse.from(facilities.save(facility));
    }

    @Transactional
    public SafetyFacilityAdminResponse update(Actor actor, long facilityId, SafetyFacilityAdminRequest request) {
        actor.requireAdmin();
        SafetyFacility facility = find(facilityId);
        facility.update(request.latitude(), request.longitude(), request.address(), request.facilityType());
        return SafetyFacilityAdminResponse.from(facility);
    }

    @Transactional
    public void delete(Actor actor, long facilityId) {
        actor.requireAdmin();
        facilities.delete(find(facilityId));
    }

    private SafetyFacility find(long facilityId) {
        return facilities.findById(facilityId).orElseThrow(() -> ApiException.notFound("안전시설"));
    }
}
