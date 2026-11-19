package com.safepath.backend.route.score;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ScorePolicy {
    public final double segmentMeters, radiusMeters, cctv, light, police, weakestWeight, distanceWeight, safestDistanceWeight;
    public ScorePolicy(@Value("${safepath.route.score.segment-meters:100}") double segmentMeters,
                       @Value("${safepath.route.score.radius-meters:80}") double radiusMeters,
                       @Value("${safepath.route.score.cctv:1}") double cctv,
                       @Value("${safepath.route.score.light:1}") double light,
                       @Value("${safepath.route.score.police:2}") double police,
                       @Value("${safepath.route.score.weakest-weight:0.2}") double weakestWeight,
                       @Value("${safepath.route.score.distance-weight:25}") double distanceWeight,
                       @Value("${safepath.route.score.safest-distance-weight:5}") double safestDistanceWeight) {
        if (segmentMeters <= 0 || radiusMeters <= 0 || cctv < 0 || light < 0 || police < 0 || weakestWeight < 0 || weakestWeight > 1 || distanceWeight < 0 || safestDistanceWeight < 0) throw new IllegalArgumentException("Invalid route score policy");
        this.segmentMeters=segmentMeters; this.radiusMeters=radiusMeters; this.cctv=cctv; this.light=light; this.police=police; this.weakestWeight=weakestWeight; this.distanceWeight=distanceWeight; this.safestDistanceWeight=safestDistanceWeight;
    }
    public double weight(String type) { return switch(type) { case "CCTV" -> cctv; case "SECURITY_LIGHT" -> light; case "POLICE" -> police; default -> 0; }; }
}
