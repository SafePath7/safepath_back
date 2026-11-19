package com.safepath.backend.route.score;

import com.safepath.backend.route.RouteCandidate;
import com.safepath.backend.route.dto.RouteRequest.Coordinate;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RouteScorer {
    private final ScorePolicy policy;
    public RouteScorer(ScorePolicy policy) { this.policy=policy; }
    public record Evaluation(Double safetyScore, double observedFraction, Map<String,Integer> counts) {}
    public Evaluation evaluate(RouteCandidate candidate, List<SafetyFactor> factors) {
        List<Coordinate> segments = segments(candidate.coordinates());
        double[] evidence = new double[segments.size()];
        Map<String,Integer> counts = new TreeMap<>();
        for (SafetyFactor factor : factors) {
            FactorResult result=factor.evaluate(segments);
            if (result.evidence().length != evidence.length) throw new IllegalArgumentException("Invalid factor result");
            for (int i=0;i<evidence.length;i++) evidence[i] += result.evidence()[i];
            result.counts().forEach((k,v)->counts.merge(k,v,Integer::sum));
        }
        int observed=0; double sum=0, min=100;
        for (double value:evidence) {
            if (value <= 0) continue;
            double score=100*(1-Math.exp(-value));
            observed++; sum+=score; min=Math.min(min,score);
        }
        if (observed==0) return new Evaluation(null,0,Map.copyOf(counts));
        return new Evaluation((1-policy.weakestWeight)*sum/observed+policy.weakestWeight*min,
                (double)observed/evidence.length,Map.copyOf(counts));
    }
    private List<Coordinate> segments(List<Coordinate> points) {
        List<Coordinate> out=new ArrayList<>();
        double remaining=policy.segmentMeters, lat=points.get(0).latitude(), lng=points.get(0).longitude();
        Coordinate start=new Coordinate(lat,lng), cursor=start;
        for (int i=1;i<points.size();i++) {
            Coordinate end=points.get(i); double length=FacilitySafetyFactor.distance(cursor,end);
            while (length >= remaining && length > 0) {
                double f=remaining/length;
                Coordinate cut=new Coordinate(cursor.latitude()+(end.latitude()-cursor.latitude())*f, cursor.longitude()+(end.longitude()-cursor.longitude())*f);
                out.add(new Coordinate((start.latitude()+cut.latitude())/2,(start.longitude()+cut.longitude())/2));
                start=cut; cursor=cut; remaining=policy.segmentMeters; length=FacilitySafetyFactor.distance(cursor,end);
            }
            remaining-=length; cursor=end;
        }
        if (remaining < policy.segmentMeters || out.isEmpty()) out.add(new Coordinate((start.latitude()+cursor.latitude())/2,(start.longitude()+cursor.longitude())/2));
        return out;
    }
}
