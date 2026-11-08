package com.safepath.backend.guardian;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import com.safepath.backend.guardian.GuardianModels.*;
import com.safepath.backend.security.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/guardian-registrations")
public class GuardianController {
    private final GuardianService service;
    private final CurrentUser users;

    public GuardianController(GuardianService service, CurrentUser users) { this.service = service; this.users = users; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Registration requestRegistration(Authentication auth, @Valid @RequestBody RegistrationRequest request) {
        return service.requestRegistration(users.resolve(auth), request.guardianId());
    }

    @PatchMapping("/{registrationId}/accept")
    public Registration acceptRegistration(Authentication auth, @PathVariable @Positive long registrationId) {
        return service.acceptRegistration(users.resolve(auth), registrationId);
    }

    @PatchMapping("/{registrationId}/reject")
    public Registration rejectRegistration(Authentication auth, @PathVariable @Positive long registrationId) {
        return service.rejectRegistration(users.resolve(auth), registrationId);
    }

    @PatchMapping("/{registrationId}/revoke")
    public Registration revokeRegistration(Authentication auth, @PathVariable @Positive long registrationId) {
        return service.revokeRegistration(users.resolve(auth), registrationId);
    }

    @PatchMapping("/{registrationId}/suspend")
    public Registration suspendRegistration(Authentication auth, @PathVariable @Positive long registrationId) {
        return service.suspendRegistration(users.resolve(auth), registrationId);
    }

    @PatchMapping("/{registrationId}/permissions")
    public Registration updatePermission(Authentication auth, @PathVariable @Positive long registrationId,
            @Valid @RequestBody PermissionRequest request) {
        return service.updatePermission(users.resolve(auth), registrationId, request);
    }

    @GetMapping("/{registrationId}/permissions/check")
    public PermissionResponse checkPermission(Authentication auth, @PathVariable @Positive long registrationId,
            @RequestParam PermissionType permissionType) {
        return service.checkPermission(users.resolve(auth), registrationId, permissionType);
    }
}
