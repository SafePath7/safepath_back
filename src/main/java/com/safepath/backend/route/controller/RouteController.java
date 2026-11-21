package com.safepath.backend.route.controller;

import com.safepath.backend.route.dto.RouteRequest;
import com.safepath.backend.route.dto.RouteResponse;
import com.safepath.backend.route.service.RouteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/routes")
public class RouteController {
    private final RouteService service;
    public RouteController(RouteService service) {
        this.service = service;
    }

    @PostMapping
    public RouteResponse recommend(@Valid @RequestBody RouteRequest request) {
        return service.recommend(request);
    }
}
