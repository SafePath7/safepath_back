package com.safepath.backend.route;

import com.safepath.backend.route.dto.RouteRequest.Coordinate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.io.IOException;
import tools.jackson.databind.ObjectMapper;
import com.safepath.backend.route.dto.RouteRequest;

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
    @Test void keepsPrimaryWhenOptionalRequestsFail() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"code":"Ok","routes":[{"distance":100,"duration":80,
                "geometry":{"coordinates":[[128.0,35.0],[128.01,35.01]]}}]}
                """);
        when(http.<String>send(any(), any())).thenReturn(response).thenThrow(new IOException("network failure"));
        var client = new MapboxRouteClient("test-token", new ObjectMapper(), http);
        var result = client.find(new RouteRequest(new Coordinate(35.0,128.0),new Coordinate(35.01,128.01)));
        assertEquals(1, result.size());
        verify(http, times(5)).send(any(), any());
    }
}
