package com.safepath.backend.route;

import com.safepath.backend.route.dto.RouteRequest;
import com.safepath.backend.route.dto.RouteResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/routes")
public class RouteController {
    private final RouteService service;
    public RouteController(RouteService service) { this.service = service; }
    @PostMapping
    public RouteResponse recommend(@Valid @RequestBody RouteRequest request) { return service.recommend(request); }
}
