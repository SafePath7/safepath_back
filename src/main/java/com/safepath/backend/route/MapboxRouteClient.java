package com.safepath.backend.route;

import com.safepath.backend.common.exception.ApiException;
import com.safepath.backend.route.dto.RouteRequest;
import com.safepath.backend.route.dto.RouteRequest.Coordinate;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class MapboxRouteClient {
    private final String token;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public MapboxRouteClient(@Value("${MAPBOX_ACCESS_TOKEN:}") String token, ObjectMapper mapper) {
        this.token = token; this.mapper = mapper;
    }
    public List<RouteCandidate> find(RouteRequest request) {
        if (token.isBlank()) throw error(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_NOT_CONFIGURED", "Mapbox ??? ???? ?????.");
        Coordinate a = request.origin(), b = request.destination();
        String url = "https://api.mapbox.com/directions/v5/mapbox/walking/" + a.longitude() + "," + a.latitude()
                + ";" + b.longitude() + "," + b.latitude()
                + "?alternatives=true&geometries=geojson&overview=full&access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        try {
            HttpRequest http = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw error(HttpStatus.BAD_GATEWAY, "ROUTING_PROVIDER_ERROR", "?? ?? ??? ??? ??????.");
            JsonNode root = mapper.readTree(response.body());
            if (!"Ok".equals(root.path("code").asText())) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ROUTE_NOT_FOUND", "?? ??? ?? ? ????.");
            List<RouteCandidate> result = new ArrayList<>();
            for (JsonNode route : root.path("routes")) {
                List<Coordinate> coordinates = new ArrayList<>();
                for (JsonNode point : route.path("geometry").path("coordinates")) {
                    if (point.isArray() && point.size() >= 2 && point.get(0).isNumber() && point.get(1).isNumber())
                        coordinates.add(new Coordinate(point.get(1).asDouble(), point.get(0).asDouble()));
                }
                double distance = route.path("distance").asDouble(-1), duration = route.path("duration").asDouble(-1);
                if (coordinates.size() >= 2 && distance > 0 && duration >= 0) result.add(new RouteCandidate(List.copyOf(coordinates), distance, duration));
            }
            if (result.isEmpty()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ROUTE_NOT_FOUND", "?? ??? ?? ? ????.");
            return result;
        } catch (tools.jackson.core.JacksonException e) {
            throw error(HttpStatus.BAD_GATEWAY, "ROUTING_PROVIDER_ERROR", "?? ?? ??? ??? ? ????.");
        } catch (HttpTimeoutException e) {
            throw error(HttpStatus.GATEWAY_TIMEOUT, "ROUTING_TIMEOUT", "?? ?? ??? ???????.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_INTERRUPTED", "?? ??? ???????.");
        } catch (java.io.IOException e) {
            throw error(HttpStatus.BAD_GATEWAY, "ROUTING_PROVIDER_ERROR", "?? ?? ???? ??? ? ????.");
        }
    }
    private static ApiException error(HttpStatus s, String c, String m) { return new ApiException(s, c, m); }
}
