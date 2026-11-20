package com.safepath.backend.route;

import com.safepath.backend.common.exception.ApiException;
import com.safepath.backend.route.dto.RouteRequest;
import com.safepath.backend.route.dto.RouteRequest.Coordinate;
import com.safepath.backend.route.score.*;
import com.safepath.backend.route.service.RouteService;
import com.safepath.backend.safety.service.SafetyFacilityService;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RouteServiceTest {
    @Test void oneCandidateIsReusedForAllThreeTypes() {
        var client=mock(MapboxRouteClient.class); var repository=mock(SafetyFacilityService.class);
        var policy=new ScorePolicy(new org.springframework.mock.env.MockEnvironment());
        var request=new RouteRequest(new Coordinate(35.0,128.0),new Coordinate(35.001,128.0));
        var candidate=new RouteCandidate(List.of(request.origin(),request.destination()),111,80);
        when(client.find(request)).thenReturn(List.of(candidate));
        when(repository.findFacilitiesInBounds(anyDouble(),anyDouble(),anyDouble(),anyDouble())).thenReturn(List.of());
        var result=new RouteService(client,repository,new RouteScorer(policy),policy).recommend(request);
        assertEquals(1,result.candidateCount()); assertEquals(3,result.routes().size());
        assertTrue(result.routes().stream().allMatch(r->r.coordinates().equals(candidate.coordinates())));
        assertEquals(List.of("RECOMMENDED","SAFEST","FASTEST"),result.routes().stream().map(r->r.type()).toList());
        verify(repository,times(1)).findFacilitiesInBounds(anyDouble(),anyDouble(),anyDouble(),anyDouble());
    }
    @Test void allCandidatesAreConsideredForFastestRoute() {
        var client=mock(MapboxRouteClient.class); var repository=mock(SafetyFacilityService.class);
        var policy=new ScorePolicy(new org.springframework.mock.env.MockEnvironment());
        var request=new RouteRequest(new Coordinate(35.0,128.0),new Coordinate(35.001,128.0));
        var shortest=new RouteCandidate(List.of(request.origin(),request.destination()),100,100);
        var longer=new RouteCandidate(List.of(request.origin(),new Coordinate(35.0005,128.0001),request.destination()),120,70);
        var third=new RouteCandidate(List.of(request.origin(),new Coordinate(35.0005,128.0002),request.destination()),130,60);
        var fourth=new RouteCandidate(List.of(request.origin(),new Coordinate(35.0005,128.0003),request.destination()),140,50);
        when(client.find(request)).thenReturn(List.of(fourth,third,longer,shortest));
        when(repository.findFacilitiesInBounds(anyDouble(),anyDouble(),anyDouble(),anyDouble())).thenReturn(List.of());
        var result=new RouteService(client,repository,new RouteScorer(policy),policy).recommend(request);
        assertEquals(4,result.candidateCount());
        assertEquals(fourth.coordinates(),result.routes().get(2).coordinates());
        assertEquals(List.of("FASTEST"),result.routes().get(0).sameAsTypes());
    }
    @Test void longerDurationLowersRecommendationWhenOtherFactorsMatch() {
        var client=mock(MapboxRouteClient.class); var facilityService=mock(SafetyFacilityService.class);
        var scorer=mock(RouteScorer.class);
        var policy=new ScorePolicy(new org.springframework.mock.env.MockEnvironment());
        var request=new RouteRequest(new Coordinate(35.0,128.0),new Coordinate(35.001,128.0));
        var slower=new RouteCandidate(List.of(request.origin(),request.destination()),100,180);
        var faster=new RouteCandidate(List.of(request.origin(),new Coordinate(35.0005,128.0001),request.destination()),100,60);
        when(client.find(request)).thenReturn(List.of(slower,faster));
        when(facilityService.findFacilitiesInBounds(anyDouble(),anyDouble(),anyDouble(),anyDouble())).thenReturn(List.of());
        when(scorer.evaluate(any(),anyList())).thenReturn(new RouteScorer.Evaluation(50.0,1,java.util.Map.of()));
        var result=new RouteService(client,facilityService,scorer,policy).recommend(request);
        assertEquals(faster.coordinates(),result.routes().get(0).coordinates());
        assertEquals(slower.coordinates(),result.routes().get(1).coordinates());
        assertEquals(faster.coordinates(),result.routes().get(2).coordinates());
    }
    @Test void recommendationBalancesSafetyAgainstExtraMinutes() {
        var client=mock(MapboxRouteClient.class); var facilities=mock(SafetyFacilityService.class);
        var scorer=mock(RouteScorer.class);
        var policy=new ScorePolicy(new org.springframework.mock.env.MockEnvironment());
        var request=new RouteRequest(new Coordinate(35.0,128.0),new Coordinate(35.001,128.0));
        var fast=new RouteCandidate(List.of(request.origin(),request.destination()),100,60);
        var safe=new RouteCandidate(List.of(request.origin(),new Coordinate(35.0005,128.0001),request.destination()),120,180);
        when(client.find(request)).thenReturn(List.of(fast,safe));
        when(facilities.findFacilitiesInBounds(anyDouble(),anyDouble(),anyDouble(),anyDouble())).thenReturn(List.of());
        when(scorer.evaluate(eq(fast),anyList())).thenReturn(new RouteScorer.Evaluation(70.0,1,java.util.Map.of()));
        when(scorer.evaluate(eq(safe),anyList())).thenReturn(new RouteScorer.Evaluation(71.5,1,java.util.Map.of()));
        var result=new RouteService(client,facilities,scorer,policy).recommend(request);
        assertEquals(fast.coordinates(),result.routes().get(0).coordinates());
        assertEquals(safe.coordinates(),result.routes().get(1).coordinates());
        assertEquals(fast.coordinates(),result.routes().get(2).coordinates());
    }
    @Test void safestUsesOnlyFacilityScoreEvenWhenSlow() {
        var client=mock(MapboxRouteClient.class); var facilityService=mock(SafetyFacilityService.class);
        var scorer=mock(RouteScorer.class);
        var policy=new ScorePolicy(new org.springframework.mock.env.MockEnvironment());
        var request=new RouteRequest(new Coordinate(35.0,128.0),new Coordinate(35.001,128.0));
        var fast=new RouteCandidate(List.of(request.origin(),request.destination()),100,60);
        var safe=new RouteCandidate(List.of(request.origin(),new Coordinate(35.0005,128.0001),request.destination()),120,360);
        when(client.find(request)).thenReturn(List.of(fast,safe));
        when(facilityService.findFacilitiesInBounds(anyDouble(),anyDouble(),anyDouble(),anyDouble())).thenReturn(List.of());
        when(scorer.evaluate(eq(fast),anyList())).thenReturn(new RouteScorer.Evaluation(70.0,1,java.util.Map.of()));
        when(scorer.evaluate(eq(safe),anyList())).thenReturn(new RouteScorer.Evaluation(80.0,1,java.util.Map.of()));
        var result=new RouteService(client,facilityService,scorer,policy).recommend(request);
        assertEquals(safe.coordinates(),result.routes().get(1).coordinates());
        assertEquals(fast.coordinates(),result.routes().get(2).coordinates());
    }
    @Test void missingMapboxTokenFailsExplicitly() {
        var client=new MapboxRouteClient("",new tools.jackson.databind.ObjectMapper());
        var request=new RouteRequest(new Coordinate(35.0,128.0),new Coordinate(35.001,128.0));
        assertEquals("ROUTING_NOT_CONFIGURED",assertThrows(ApiException.class,()->client.find(request)).code());
    }
}
