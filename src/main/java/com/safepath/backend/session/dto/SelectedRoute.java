package com.safepath.backend.session.dto;

import com.safepath.backend.route.dto.RouteRequest.Coordinate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SelectedRoute(
        @NotNull @Pattern(regexp = "RECOMMENDED|SAFEST|FASTEST") String type,
        @NotNull @Size(min = 2, max = 10000) List<@NotNull @Valid Coordinate> coordinates,
        @NotNull @DecimalMin("0.0") @DecimalMax("20000.0") Double distanceMeters,
        @NotNull @DecimalMin("0.0") Double durationSeconds,
        @DecimalMin("0.0") @DecimalMax("100.0") Double safetyScore,
        @Size(max = 200) String destinationName) {
    public SelectedRoute {
        if (coordinates != null) coordinates = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(coordinates));
    }
}
