package com.safepath.backend.safety.controller;

import java.util.List;

import com.safepath.backend.safety.dto.SafetyFacilityResponse;
import com.safepath.backend.safety.service.SafetyFacilityService;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/safety-facilities")
public class SafetyFacilityController {
    private final SafetyFacilityService service;

    public SafetyFacilityController(SafetyFacilityService service) {
        this.service = service;
    }

    @GetMapping
    public List<SafetyFacilityResponse> findInBounds(
            @RequestParam @Min(0) @Max(22) int zoom,
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double southWestLat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double southWestLng,
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double northEastLat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double northEastLng) {
        return service.findInBounds(zoom, southWestLat, southWestLng, northEastLat, northEastLng);
    }
}
