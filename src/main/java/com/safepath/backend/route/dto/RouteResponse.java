package com.safepath.backend.route.dto;

import java.util.List;
import java.util.Map;

public record RouteResponse(int candidateCount, List<Route> routes) {
    public record Route(String type, List<RouteRequest.Coordinate> coordinates, double distanceMeters,
                        double durationSeconds, Double safetyScore, double observedFraction,
                        Map<String, Integer> safetyFactors, List<String> sameAsTypes) {}
}
