package com.safepath.backend.route.score;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ScorePolicy {
    private static final String PREFIX = "safepath.route.score.";
    public final double segmentMeters, radiusMeters, weakestWeight, distanceWeight, safestDistanceWeight;
    public final double durationWeight, safestDurationWeight;
    private final Map<FacilityScore, Double> weights;

    public ScorePolicy(Environment environment) {
        segmentMeters = value(environment, "segment-meters", 100);
        radiusMeters = value(environment, "radius-meters", 80);
        weakestWeight = value(environment, "weakest-weight", 0.2);
        distanceWeight = value(environment, "distance-weight", 25);
        safestDistanceWeight = value(environment, "safest-distance-weight", 5);
        durationWeight = value(environment, "duration-weight", 1.0);
        safestDurationWeight = value(environment, "safest-duration-weight", 0.1);
        if (segmentMeters <= 0 || radiusMeters <= 0 || weakestWeight < 0 || weakestWeight > 1
                || distanceWeight < 0 || safestDistanceWeight < 0 || durationWeight < 0 || safestDurationWeight < 0) {
            throw new IllegalArgumentException("Invalid route score policy");
        }
        EnumMap<FacilityScore, Double> configured = new EnumMap<>(FacilityScore.class);
        for (FacilityScore facility : FacilityScore.values()) {
            double weight = value(environment, facility.property(), facility.defaultWeight());
            if (weight < 0) throw new IllegalArgumentException("Invalid facility weight: " + facility);
            configured.put(facility, weight);
        }
        weights = Map.copyOf(configured);
    }

    private static double value(Environment environment, String name, double defaultValue) {
        double result = environment.getProperty(PREFIX + name, Double.class, defaultValue);
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Invalid route score property: " + name);
        return result;
    }

    public double weight(String type) {
        if (type == null) return 0;
        try {
            return weights.get(FacilityScore.valueOf(type));
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }
}
