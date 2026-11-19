package com.safepath.backend.route.score;

import com.safepath.backend.route.dto.RouteRequest.Coordinate;
import com.safepath.backend.safety.dto.SafetyFacilityResponse;
import java.util.*;

public class FacilitySafetyFactor implements SafetyFactor {
    private final List<SafetyFacilityResponse> facilities;
    private final ScorePolicy policy;
    public FacilitySafetyFactor(List<SafetyFacilityResponse> facilities, ScorePolicy policy) { this.facilities=facilities; this.policy=policy; }
    @Override public FactorResult evaluate(List<Coordinate> segments) {
        double[] evidence = new double[segments.size()];
        Map<String,Integer> counts = new TreeMap<>();
        Set<Long> seen = new HashSet<>();
        for (SafetyFacilityResponse facility : facilities) {
            double weight = policy.weight(facility.facilityType());
            if (weight <= 0 || !seen.add(facility.id())) continue;
            Coordinate point = new Coordinate(facility.latitude(), facility.longitude());
            int nearest = -1; double min = policy.radiusMeters;
            for (int i=0; i<segments.size(); i++) {
                double distance = distance(point, segments.get(i));
                if (distance <= min) { min=distance; nearest=i; }
            }
            if (nearest >= 0) {
                evidence[nearest] += weight * Math.pow(1 - min / policy.radiusMeters, 2);
                counts.merge(facility.facilityType(), 1, Integer::sum);
            }
        }
        return new FactorResult(evidence, Map.copyOf(counts));
    }
    public static double distance(Coordinate a, Coordinate b) {
        double lat1=Math.toRadians(a.latitude()), lat2=Math.toRadians(b.latitude());
        double dlat=lat2-lat1, dlng=Math.toRadians(b.longitude()-a.longitude());
        double h=Math.pow(Math.sin(dlat/2),2)+Math.cos(lat1)*Math.cos(lat2)*Math.pow(Math.sin(dlng/2),2);
        return 12742000 * Math.asin(Math.sqrt(Math.min(1,h)));
    }
}
