package com.safepath.backend.route.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record RouteRequest(@NotNull @Valid Coordinate origin, @NotNull @Valid Coordinate destination) {
    public record Coordinate(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                             @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}
}
