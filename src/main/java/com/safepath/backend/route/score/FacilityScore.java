package com.safepath.backend.route.score;

/** Facility type, configuration key, and provisional default weight. */
public enum FacilityScore {
    CCTV("cctv", 1.0),
    SECURITY_LIGHT("light", 1.0),
    POLICE("police", 2.0),
    EMERGENCY_CALL_BOX("emergency-call-box", 1.5),
    SAFETY_GUARDIAN_STORE("safety-guardian-store", 1.0);

    private final String property;
    private final double defaultWeight;

    FacilityScore(String property, double defaultWeight) {
        this.property = property;
        this.defaultWeight = defaultWeight;
    }

    public String property() { return property; }
    public double defaultWeight() { return defaultWeight; }
}
