package com.safepath.backend.risk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EventStatusRequest(@NotBlank @Size(max = 20) String status) {}
