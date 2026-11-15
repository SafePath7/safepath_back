package com.safepath.backend.session.dto;

import java.util.List;

public record StartSessionRequest(List<Long> guardianIds) {
}
