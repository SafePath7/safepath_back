package com.safepath.backend.route;

import com.safepath.backend.common.exception.ApiException;
import com.safepath.backend.route.dto.RouteRequest;
import com.safepath.backend.route.dto.RouteRequest.Coordinate;
import com.safepath.backend.route.score.*;
import com.safepath.backend.safety.repository.SafetyFacilityRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RouteServiceTest {
    @Test void oneCandidateIsReusedForAllThreeTypes() {
        var client=mock(MapboxRouteClient.class); var repository=mock(SafetyFacilityRepository.class);
        var policy=new ScorePolicy(100,80,1,1,2,0.2,25,5);
        var request=new RouteRequest(new Coordinate(35.0,128.0),new Coordinate(35.001,128.0));
        var candidate=new RouteCandidate(List.of(request.origin(),request.destination()),111,80);
        when(client.find(request)).thenReturn(List.of(candidate));
        when(repository.findByLatitudeBetweenAndLongitudeBetweenOrderByIdAsc(anyDouble(),anyDouble(),anyDouble(),anyDouble())).thenReturn(List.of());
        var result=new RouteService(client,repository,new RouteScorer(policy),policy).recommend(request);
        assertEquals(1,result.candidateCount()); assertEquals(3,result.routes().size());
        assertTrue(result.routes().stream().allMatch(r->r.coordinates().equals(candidate.coordinates())));
        assertEquals(List.of("RECOMMENDED","SAFEST","SHORTEST"),result.routes().stream().map(r->r.type()).toList());
        verify(repository,times(1)).findByLatitudeBetweenAndLongitudeBetweenOrderByIdAsc(anyDouble(),anyDouble(),anyDouble(),anyDouble());
    }
    @Test void missingMapboxTokenFailsExplicitly() {
        var client=new MapboxRouteClient("",new tools.jackson.databind.ObjectMapper());
        var request=new RouteRequest(new Coordinate(35.0,128.0),new Coordinate(35.001,128.0));
        assertEquals("ROUTING_NOT_CONFIGURED",assertThrows(ApiException.class,()->client.find(request)).code());
    }
}
