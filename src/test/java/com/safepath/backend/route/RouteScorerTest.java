package com.safepath.backend.route;

import com.safepath.backend.route.dto.RouteRequest.Coordinate;
import com.safepath.backend.route.score.*;
import com.safepath.backend.safety.entity.SafetyFacility;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RouteScorerTest {
    private final ScorePolicy policy = new ScorePolicy(100,80,1,1,2,0.2,25,5);
    private final RouteScorer scorer = new RouteScorer(policy);
    private final RouteCandidate route = new RouteCandidate(List.of(new Coordinate(35.0,128.0),new Coordinate(35.0009,128.0)),100,75);
    @Test void noDataIsUnknown() {
        var result=scorer.evaluate(route,List.of(new FacilitySafetyFactor(List.of(),policy)));
        assertNull(result.safetyScore()); assertEquals(0,result.observedFraction());
    }
    @Test void duplicateFacilitiesAreCountedOnce() {
        SafetyFacility facility=mock(SafetyFacility.class);
        when(facility.getId()).thenReturn(1L); when(facility.getLatitude()).thenReturn(35.00045);
        when(facility.getLongitude()).thenReturn(128.0); when(facility.getFacilityType()).thenReturn("CCTV");
        var one=scorer.evaluate(route,List.of(new FacilitySafetyFactor(List.of(facility),policy)));
        var two=scorer.evaluate(route,List.of(new FacilitySafetyFactor(List.of(facility,facility),policy)));
        assertEquals(one.safetyScore(),two.safetyScore()); assertEquals(1,two.counts().get("CCTV"));
    }
    @Test void policeHasGreaterWeight() {
        assertTrue(policy.weight("POLICE") > policy.weight("CCTV")); assertEquals(0,policy.weight("UNKNOWN"));
    }
    @Test void newFactorCanSupplyEvidence() {
        SafetyFactor factor=segments -> new FactorResult(new double[segments.size()],Map.of());
        assertNull(scorer.evaluate(route,List.of(factor)).safetyScore());
    }
}
