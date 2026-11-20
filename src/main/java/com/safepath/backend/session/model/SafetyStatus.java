package com.safepath.backend.session.model;

/** Display states; the source and thresholds for future risk assessment are not yet defined. */
public enum SafetyStatus {
    UNKNOWN,
    SAFE,
    RISK_SUSPECTED,
    EMERGENCY
}
