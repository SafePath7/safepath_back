package com.safepath.backend.route;

import com.safepath.backend.route.dto.RouteRequest.Coordinate;
import com.safepath.backend.route.score.*;
import com.safepath.backend.safety.dto.SafetyFacilityResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteScorerTest {
    private final ScorePolicy policy = new ScorePolicy(new org.springframework.mock.env.MockEnvironment());
    private final RouteScorer scorer = new RouteScorer(policy);
    private final RouteCandidate route = new RouteCandidate(List.of(new Coordinate(35.0,128.0),new Coordinate(35.0009,128.0)),100,75);
    @Test void noDataIsUnknown() {
        var result=scorer.evaluate(route,List.of(new FacilitySafetyFactor(List.of(),policy)));
        assertNull(result.safetyScore()); assertEquals(0,result.observedFraction());
    }
    @Test void duplicateFacilitiesAreCountedOnce() {
        SafetyFacilityResponse facility = new SafetyFacilityResponse(1L, 35.00045, 128.0, "address", "CCTV");
        var one=scorer.evaluate(route,List.of(new FacilitySafetyFactor(List.of(facility),policy)));
        var two=scorer.evaluate(route,List.of(new FacilitySafetyFactor(List.of(facility,facility),policy)));
        assertEquals(one.safetyScore(),two.safetyScore()); assertEquals(1,two.counts().get("CCTV"));
    }
    @Test void policeHasGreaterWeight() {
        assertTrue(policy.weight("POLICE") > policy.weight("CCTV")); assertEquals(0,policy.weight("UNKNOWN"));
    }
    @Test void facilityWeightCanBeConfiguredWithoutChangingDefaults() {
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("safepath.route.score.cctv", "3.5");
        var configured = new ScorePolicy(environment);
        assertEquals(3.5, configured.weight("CCTV"));
        assertEquals(1.0, configured.weight("SECURITY_LIGHT"));
        assertEquals(2.0, configured.weight("POLICE"));
        assertEquals(1.5, configured.weight("EMERGENCY_CALL_BOX"));
        assertEquals(1.0, configured.weight("SAFETY_GUARDIAN_STORE"));
        assertEquals(0, configured.weight("UNKNOWN"));
    }
    @Test void newFactorCanSupplyEvidence() {
        SafetyFactor factor=segments -> new FactorResult(new double[segments.size()],Map.of());
        assertNull(scorer.evaluate(route,List.of(factor)).safetyScore());
    }
}
