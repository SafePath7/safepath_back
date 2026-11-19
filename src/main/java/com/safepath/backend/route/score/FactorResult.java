package com.safepath.backend.route.score;

import java.util.Map;

public record FactorResult(double[] evidence, Map<String, Integer> counts) {}
