package com.safepath.backend.session.dto;

import java.util.List;
import jakarta.validation.Valid;

public record StartSessionRequest(List<Long> guardianIds, @Valid SelectedRoute selectedRoute) {
}
