package com.safepath.backend.risk;

import java.util.Optional;
import java.util.UUID;

import com.safepath.backend.risk.RiskModels.Location;
import com.safepath.backend.risk.RiskModels.LocationRequest;

/**
 * Extension point for the project's approved location persistence module.
 * V1 has no location table, so no default implementation or schema is introduced.
 * Implementations participate in the caller's transaction and use persisted session IDs.
 */
public interface RiskLocationStorage {
    Location saveLocation(LocationRequest request);
    Optional<Location> getLatestLocation(UUID sessionId);
}
