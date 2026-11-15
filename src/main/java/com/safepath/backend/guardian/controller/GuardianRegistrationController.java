package com.safepath.backend.guardian.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import com.safepath.backend.common.auth.CurrentUser;
import com.safepath.backend.guardian.dto.PermissionRequest;
import com.safepath.backend.guardian.dto.PermissionResponse;
import com.safepath.backend.guardian.dto.RegistrationRequest;
import com.safepath.backend.guardian.dto.RegistrationResponse;
import com.safepath.backend.guardian.dto.RegistrationRole;
import com.safepath.backend.guardian.dto.RegistrationSummaryResponse;
import com.safepath.backend.guardian.entity.PermissionType;
import com.safepath.backend.guardian.entity.RegistrationStatus;
import com.safepath.backend.guardian.service.GuardianRegistrationService;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/guardian-registrations")
public class GuardianRegistrationController {
    private final GuardianRegistrationService service;
    private final CurrentUser users;

    public GuardianRegistrationController(GuardianRegistrationService service, CurrentUser users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RegistrationResponse requestRegistration(Authentication auth,
            @Valid @RequestBody RegistrationRequest request) {
        return service.requestRegistration(users.resolve(auth), request.guardianId());
    }

    /** role=USER: guardians I asked for; role=GUARDIAN: requests sent to me; omitted: both. */
    @GetMapping
    public List<RegistrationSummaryResponse> listRegistrations(Authentication auth,
            @RequestParam(required = false) RegistrationRole role,
            @RequestParam(required = false) RegistrationStatus status) {
        return service.listRegistrations(users.resolve(auth), role, status);
    }

    @PatchMapping("/{registrationId}/accept")
    public RegistrationResponse acceptRegistration(Authentication auth, @PathVariable @Positive long registrationId) {
        return service.acceptRegistration(users.resolve(auth), registrationId);
    }

    @PatchMapping("/{registrationId}/reject")
    public RegistrationResponse rejectRegistration(Authentication auth, @PathVariable @Positive long registrationId) {
        return service.rejectRegistration(users.resolve(auth), registrationId);
    }

    @PatchMapping("/{registrationId}/revoke")
    public RegistrationResponse revokeRegistration(Authentication auth, @PathVariable @Positive long registrationId) {
        return service.revokeRegistration(users.resolve(auth), registrationId);
    }

    @PatchMapping("/{registrationId}/suspend")
    public RegistrationResponse suspendRegistration(Authentication auth, @PathVariable @Positive long registrationId) {
        return service.suspendRegistration(users.resolve(auth), registrationId);
    }

    @PatchMapping("/{registrationId}/permissions")
    public RegistrationResponse updatePermission(Authentication auth, @PathVariable @Positive long registrationId,
            @Valid @RequestBody PermissionRequest request) {
        return service.updatePermission(users.resolve(auth), registrationId, request);
    }

    @GetMapping("/{registrationId}/permissions/check")
    public PermissionResponse checkPermission(Authentication auth, @PathVariable @Positive long registrationId,
            @RequestParam PermissionType permissionType) {
        return service.checkPermission(users.resolve(auth), registrationId, permissionType);
    }
}
