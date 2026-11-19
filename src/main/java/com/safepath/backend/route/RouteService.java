package com.safepath.backend.route;

import com.safepath.backend.common.exception.ApiException;
import com.safepath.backend.route.dto.RouteRequest;
import com.safepath.backend.route.dto.RouteRequest.Coordinate;
import com.safepath.backend.route.dto.RouteResponse;
import com.safepath.backend.route.score.*;
import com.safepath.backend.safety.dto.SafetyFacilityResponse;
import com.safepath.backend.safety.service.SafetyFacilityService;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RouteService {
    private final MapboxRouteClient client;
    private final SafetyFacilityService facilityService;
    private final RouteScorer scorer;
    private final ScorePolicy policy;
    public RouteService(MapboxRouteClient client, SafetyFacilityService facilityService, RouteScorer scorer, ScorePolicy policy) {
        this.client=client; this.facilityService=facilityService; this.scorer=scorer; this.policy=policy;
    }
    private record Scored(RouteCandidate candidate, RouteScorer.Evaluation evaluation) {}
    public RouteResponse recommend(RouteRequest request) {
        if (FacilitySafetyFactor.distance(request.origin(),request.destination()) < 1) throw ApiException.badRequest("???? ???? ?? ?????.");
        List<RouteCandidate> found=client.find(request);
        List<RouteCandidate> candidates=found;
        if (candidates.stream().anyMatch(c -> c.distanceMeters()>20000)) throw ApiException.badRequest("20km ??? ?? ??? ?????.");
        List<Coordinate> all=candidates.stream().flatMap(c->c.coordinates().stream()).toList();
        double minLat=all.stream().mapToDouble(Coordinate::latitude).min().orElseThrow();
        double maxLat=all.stream().mapToDouble(Coordinate::latitude).max().orElseThrow();
        double minLng=all.stream().mapToDouble(Coordinate::longitude).min().orElseThrow();
        double maxLng=all.stream().mapToDouble(Coordinate::longitude).max().orElseThrow();
        double padLat=policy.radiusMeters/110000.0;
        double cosine=Math.cos(Math.toRadians(Math.max(Math.abs(minLat), Math.abs(maxLat))));
        if (cosine < 0.01 || maxLng-minLng>180) throw ApiException.badRequest("???? ?? ?? ?????.");
        double padLng=padLat/cosine;
        List<SafetyFacilityResponse> facilities;
        try { facilities=facilityService.findFacilitiesInBounds(Math.max(-90,minLat-padLat),Math.min(90,maxLat+padLat),Math.max(-180,minLng-padLng),Math.min(180,maxLng+padLng)); }
        catch (DataAccessException e) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"SAFETY_DATA_UNAVAILABLE","???? ??? ??????."); }
        List<SafetyFactor> factors=List.of(new FacilitySafetyFactor(facilities,policy));
        List<Scored> scored=candidates.stream().map(c->new Scored(c,scorer.evaluate(c,factors))).toList();
        Scored shortest=scored.stream().min(Comparator.comparingDouble(s->s.candidate().distanceMeters())).orElseThrow();
        double fastestSeconds = scored.stream().mapToDouble(s -> s.candidate().durationSeconds()).min().orElseThrow();
        Scored recommended=scored.stream().max(Comparator.comparingDouble(s->value(s,fastestSeconds,policy.durationWeight))).orElseThrow();
        Scored fastest=scored.stream().min(Comparator.comparingDouble(s -> s.candidate().durationSeconds())).orElseThrow();
        Scored safest=scored.stream().filter(s -> s.evaluation().safetyScore() != null)
                .max(Comparator.comparingDouble(s -> s.evaluation().safetyScore())).orElse(shortest);
        Map<String, Scored> selected = new LinkedHashMap<>();
        selected.put("RECOMMENDED", recommended);
        selected.put("SAFEST", safest);
        selected.put("FASTEST", fastest);
        return new RouteResponse(found.size(), selected.entrySet().stream()
                .map(entry -> response(entry.getKey(), entry.getValue(), selected)).toList());
    }
    private double value(Scored s,double fastestSeconds,double durationPenalty) {
        Double score=s.evaluation().safetyScore();
        return (score == null ? 50 : score)
                - durationPenalty*(s.candidate().durationSeconds()-fastestSeconds)/60.0;
    }
    private RouteResponse.Route response(String type, Scored s, Map<String, Scored> selected) {
        var c=s.candidate(); var e=s.evaluation();
        List<String> sameAsTypes = selected.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(type) && entry.getValue().candidate().coordinates().equals(c.coordinates()))
                .map(Map.Entry::getKey).toList();
        return new RouteResponse.Route(type,c.coordinates(),c.distanceMeters(),c.durationSeconds(),e.safetyScore(),e.observedFraction(),e.counts(),sameAsTypes);
    }
}
