package com.safepath.backend.route.score;

import com.safepath.backend.route.dto.RouteRequest.Coordinate;
import java.util.List;

/** Provides evidence for each segment; an empty segment represents unknown coverage. */
public interface SafetyFactor {
    FactorResult evaluate(List<Coordinate> segments);
}
