package com.safepath.backend.route;
import com.safepath.backend.route.dto.RouteRequest.Coordinate;
import java.util.List;
public record RouteCandidate(List<Coordinate> coordinates, double distanceMeters, double durationSeconds) {}
