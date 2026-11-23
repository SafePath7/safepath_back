package com.safepath.backend.route.deviation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.safepath.backend.route.dto.RouteRequest.Coordinate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteDistanceCalculatorTest {
    private final RouteDistanceCalculator calculator = new RouteDistanceCalculator();

    @Test
    void measuresDistanceToSegmentRatherThanOnlyVertices() {
        var route = List.of(new Coordinate(0.0, 0.0), new Coordinate(0.0, 0.01));
        assertEquals(111.2, calculator.distanceMeters(new Coordinate(0.001, 0.005), route), 0.5);
    }

    @Test
    void handlesEndpointsAndRepeatedPoints() {
        var route = List.of(new Coordinate(0.0, 0.0), new Coordinate(0.0, 0.0));
        assertEquals(111.2, calculator.distanceMeters(new Coordinate(0.001, 0.0), route), 0.5);
    }

    @Test
    void rejectsInvalidRoutes() {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.distanceMeters(new Coordinate(0.0, 0.0), List.of(new Coordinate(0.0, 0.0))));
    }
}
