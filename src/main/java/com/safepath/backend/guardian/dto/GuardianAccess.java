package com.safepath.backend.guardian.dto;

/**
 * What a guardian may currently see of a user. Every permission is false unless the
 * registration is ACCEPTED, so callers can use the permission flags directly.
 */
public record GuardianAccess(boolean accepted, boolean canViewLocation, boolean canViewStatus) {
    public static final GuardianAccess NONE = new GuardianAccess(false, false, false);
}
