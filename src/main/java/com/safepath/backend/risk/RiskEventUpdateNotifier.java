package com.safepath.backend.risk;

/**
 * Integration boundary owned by the risk module, not a notification implementation.
 * TeamRiskEventUpdateNotifier delegates to the teammate's existing service.
 * Recipient selection and delivery belong to that service.
 */
@FunctionalInterface
public interface RiskEventUpdateNotifier {
    void notifyRiskEventUpdate(long userId, String eventType);
}
