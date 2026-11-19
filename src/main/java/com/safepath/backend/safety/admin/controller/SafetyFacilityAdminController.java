package com.safepath.backend.safety.admin.controller;

import com.safepath.backend.common.auth.CurrentUser;
import com.safepath.backend.safety.admin.dto.SafetyFacilityAdminListResponse;
import com.safepath.backend.safety.admin.dto.SafetyFacilityAdminRequest;
import com.safepath.backend.safety.admin.dto.SafetyFacilityAdminResponse;
import com.safepath.backend.safety.admin.service.SafetyFacilityAdminService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/admin/safety-facilities")
public class SafetyFacilityAdminController {
    private final SafetyFacilityAdminService service;
    private final CurrentUser users;

    public SafetyFacilityAdminController(SafetyFacilityAdminService service, CurrentUser users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public SafetyFacilityAdminListResponse list(Authentication auth,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return service.list(users.resolve(auth), page, size);
    }

    @GetMapping("/{facilityId}")
    public SafetyFacilityAdminResponse get(Authentication auth, @PathVariable @Positive long facilityId) {
        return service.get(users.resolve(auth), facilityId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SafetyFacilityAdminResponse create(Authentication auth, @Valid @RequestBody SafetyFacilityAdminRequest request) {
        return service.create(users.resolve(auth), request);
    }

    @PatchMapping("/{facilityId}")
    public SafetyFacilityAdminResponse update(Authentication auth, @PathVariable @Positive long facilityId,
            @Valid @RequestBody SafetyFacilityAdminRequest request) {
        return service.update(users.resolve(auth), facilityId, request);
    }

    @DeleteMapping("/{facilityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable @Positive long facilityId) {
        service.delete(users.resolve(auth), facilityId);
    }
}
