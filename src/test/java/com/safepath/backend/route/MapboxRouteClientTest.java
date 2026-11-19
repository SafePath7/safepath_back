package com.safepath.backend.route;

import com.safepath.backend.route.dto.RouteRequest.Coordinate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MapboxRouteClientTest {
    @Test void mergesOnlyDistinctReturnedGeometries() {
        var start = new Coordinate(35.0, 128.0);
        var end = new Coordinate(35.01, 128.01);
        var first = new RouteCandidate(List.of(start, end), 100, 70);
        var duplicate = new RouteCandidate(List.of(start, end), 101, 71);
        var alternative = new RouteCandidate(List.of(start, new Coordinate(35.005,128.001), end), 130, 90);
        var candidates = new ArrayList<RouteCandidate>();
        MapboxRouteClient.mergeCandidates(candidates, List.of(first));
        MapboxRouteClient.mergeCandidates(candidates, List.of(duplicate, alternative));
        assertEquals(List.of(first, alternative), candidates);
    }
}
