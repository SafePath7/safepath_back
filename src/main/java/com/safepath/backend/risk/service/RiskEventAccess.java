package com.safepath.backend.risk.service;

import com.safepath.backend.common.auth.CurrentUser.Actor;
import com.safepath.backend.guardian.entity.PermissionType;
import com.safepath.backend.guardian.service.GuardianRegistrationService;

import org.springframework.stereotype.Component;

/** Who may read a member's risk events and the media attached to them. */
@Component
public class RiskEventAccess {
    private final GuardianRegistrationService guardians;

    public RiskEventAccess(GuardianRegistrationService guardians) {
        this.guardians = guardians;
    }

    /**
     * The member themself, a guardian whose ACCEPTED registration grants LOCATION, or an
     * admin. The admin lookup is a Firebase call, so it is tried last.
     */
    public void requireViewer(Actor actor, long userId) {
        if (actor.id() == userId) return;
        if (guardians.hasGuardianPermission(userId, actor.id(), PermissionType.LOCATION)) return;
        actor.requireAdmin();
    }
}
