package com.safepath.backend.risk;

import com.safepath.backend.guardian.GuardianModels.PermissionType;
import com.safepath.backend.guardian.GuardianRegistrationService;
import com.safepath.backend.guardianrisk.CurrentUser.Actor;

import org.springframework.stereotype.Component;

/** Who may read a member's risk events and the media attached to them. */
@Component
class RiskEventAccess {
    private final GuardianRegistrationService guardians;

    RiskEventAccess(GuardianRegistrationService guardians) { this.guardians = guardians; }

    /**
     * The member themself, a guardian whose ACCEPTED registration grants STATUS, or an
     * admin. The admin lookup is a Firebase call, so it is tried last.
     */
    void requireViewer(Actor actor, long userId) {
        if (actor.id() == userId) return;
        if (guardians.hasGuardianPermission(userId, actor.id(), PermissionType.STATUS)) return;
        actor.requireAdmin();
    }
}
