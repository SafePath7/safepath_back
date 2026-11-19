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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class MapboxRouteClient {
    private static final Logger log = LoggerFactory.getLogger(MapboxRouteClient.class);
    private final String token;
    private final ObjectMapper mapper;
    private final HttpClient client;
    @org.springframework.beans.factory.annotation.Autowired
    public MapboxRouteClient(@Value("${MAPBOX_ACCESS_TOKEN:}") String token, ObjectMapper mapper) {
        this(token, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }
    MapboxRouteClient(String token, ObjectMapper mapper, HttpClient client) {
        this.token = token; this.mapper = mapper; this.client = client;
    }
    public List<RouteCandidate> find(RouteRequest request) {
        if (token.isBlank()) throw error(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_NOT_CONFIGURED", "Mapbox ??? ???? ?????.");
        List<RouteCandidate> candidates = new ArrayList<>();
        List<RouteCandidate> primary = fetch(request, null);
        mergeCandidates(candidates, primary);
        log.info("Mapbox walking primary completed: returned={}, unique={}", primary.size(), candidates.size());
        if (candidates.size() < 6) {
            for (String bias : List.of("1", "-1", "0.5", "-0.5")) {
                if (candidates.size() >= 6) break;
                try {
                    List<RouteCandidate> additional = fetch(request, bias);
                    int before = candidates.size();
                    mergeCandidates(candidates, additional);
                    log.info("Mapbox walking alternative completed: bias={}, returned={}, added={}, unique={}",
                            bias, additional.size(), candidates.size() - before, candidates.size());
                } catch (ApiException e) {
                    if (Thread.currentThread().isInterrupted()) throw e;
                    log.warn("Mapbox walking alternative failed: bias={}, code={}, status={}",
                            bias, e.code(), e.status().value());
                }
            }
        }
        return candidates.stream().sorted(java.util.Comparator.comparingDouble(RouteCandidate::distanceMeters))
                .limit(6).toList();
    }

    static void mergeCandidates(List<RouteCandidate> candidates, List<RouteCandidate> additional) {
        for (RouteCandidate candidate : additional) {
            if (candidates.stream().noneMatch(existing -> existing.coordinates().equals(candidate.coordinates()))) {
                candidates.add(candidate);
            }
        }
    }

    private List<RouteCandidate> fetch(RouteRequest request, String bias) {
        Coordinate a = request.origin(), b = request.destination();
        String url = "https://api.mapbox.com/directions/v5/mapbox/walking/" + a.longitude() + "," + a.latitude()
                + ";" + b.longitude() + "," + b.latitude()
                + "?alternatives=true&geometries=geojson&overview=full"
                + (bias == null ? "" : "&walkway_bias=" + bias)
                + "&access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        try {
            HttpRequest http = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Mapbox walking provider HTTP error: status={}", response.statusCode());
                throw error(HttpStatus.BAD_GATEWAY, "ROUTING_PROVIDER_ERROR", "?? ?? ??? ??? ??????.");
            }
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
