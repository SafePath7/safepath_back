package com.safepath.backend.route.deviation;

import com.safepath.backend.route.dto.RouteRequest.Coordinate;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Calculates the shortest distance to the route segments in meters. */
@Component
public class RouteDistanceCalculator {
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    public double distanceMeters(Coordinate position, List<Coordinate> route) {
        validate(position);
        Objects.requireNonNull(route, "route must not be null");
        if (route.size() < 2) throw new IllegalArgumentException("route requires at least two points");

        double minimum = Double.POSITIVE_INFINITY;
        Coordinate previous = route.get(0);
        validate(previous);
        for (int i = 1; i < route.size(); i++) {
            Coordinate next = route.get(i);
            validate(next);
            minimum = Math.min(minimum, segmentDistance(position, previous, next));
            previous = next;
        }
        return minimum;
    }

    private double segmentDistance(Coordinate position, Coordinate start, Coordinate end) {
        double latitude = Math.toRadians(position.latitude());
        double ax = longitudeOffset(start.longitude(), position.longitude()) * Math.cos(latitude) * EARTH_RADIUS_METERS;
        double ay = Math.toRadians(start.latitude() - position.latitude()) * EARTH_RADIUS_METERS;
        double bx = longitudeOffset(end.longitude(), position.longitude()) * Math.cos(latitude) * EARTH_RADIUS_METERS;
        double by = Math.toRadians(end.latitude() - position.latitude()) * EARTH_RADIUS_METERS;
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / lengthSquared));
        return Math.hypot(ax + t * dx, ay + t * dy);
    }

    private double longitudeOffset(double longitude, double origin) {
        return Math.toRadians(Math.IEEEremainder(longitude - origin, 360));
    }

    private void validate(Coordinate coordinate) {
        if (coordinate == null || coordinate.latitude() == null || coordinate.longitude() == null
                || !Double.isFinite(coordinate.latitude()) || !Double.isFinite(coordinate.longitude())
                || Math.abs(coordinate.latitude()) > 90 || Math.abs(coordinate.longitude()) > 180) {
            throw new IllegalArgumentException("invalid coordinate");
        }
    }
}
