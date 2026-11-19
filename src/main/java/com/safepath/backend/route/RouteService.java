package com.safepath.backend.route;

import com.safepath.backend.common.exception.ApiException;
import com.safepath.backend.route.dto.RouteRequest;
import com.safepath.backend.route.dto.RouteRequest.Coordinate;
import com.safepath.backend.route.dto.RouteResponse;
import com.safepath.backend.route.score.*;
import com.safepath.backend.safety.entity.SafetyFacility;
import com.safepath.backend.safety.repository.SafetyFacilityRepository;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RouteService {
    private final MapboxRouteClient client;
    private final SafetyFacilityRepository repository;
    private final RouteScorer scorer;
    private final ScorePolicy policy;
    public RouteService(MapboxRouteClient client, SafetyFacilityRepository repository, RouteScorer scorer, ScorePolicy policy) {
        this.client=client; this.repository=repository; this.scorer=scorer; this.policy=policy;
    }
    private record Scored(RouteCandidate candidate, RouteScorer.Evaluation evaluation) {}
    public RouteResponse recommend(RouteRequest request) {
        if (FacilitySafetyFactor.distance(request.origin(),request.destination()) < 1) throw ApiException.badRequest("???? ???? ?? ?????.");
        List<RouteCandidate> candidates=client.find(request);
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
        List<SafetyFacility> facilities;
        try { facilities=repository.findByLatitudeBetweenAndLongitudeBetweenOrderByIdAsc(Math.max(-90,minLat-padLat),Math.min(90,maxLat+padLat),Math.max(-180,minLng-padLng),Math.min(180,maxLng+padLng)); }
        catch (DataAccessException e) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"SAFETY_DATA_UNAVAILABLE","???? ??? ??????."); }
        List<SafetyFactor> factors=List.of(new FacilitySafetyFactor(facilities,policy));
        List<Scored> scored=candidates.stream().map(c->new Scored(c,scorer.evaluate(c,factors))).toList();
        Scored shortest=scored.stream().min(Comparator.comparingDouble(s->s.candidate().distanceMeters())).orElseThrow();
        double base=shortest.candidate().distanceMeters();
        Scored recommended=scored.stream().max(Comparator.comparingDouble(s->value(s,base,policy.distanceWeight))).orElseThrow();
        Scored safest=scored.stream().max(Comparator.comparingDouble(s->value(s,base,policy.safestDistanceWeight))).orElseThrow();
        return new RouteResponse(candidates.size(),List.of(response("RECOMMENDED",recommended),response("SAFEST",safest),response("SHORTEST",shortest)));
    }
    private double value(Scored s,double shortest,double penalty) {
        Double score=s.evaluation().safetyScore();
        return (score == null ? 50 : score) - penalty*(s.candidate().distanceMeters()/shortest-1);
    }
    private RouteResponse.Route response(String type, Scored s) {
        var c=s.candidate(); var e=s.evaluation();
        return new RouteResponse.Route(type,c.coordinates(),c.distanceMeters(),c.durationSeconds(),e.safetyScore(),e.observedFraction(),e.counts());
    }
}
