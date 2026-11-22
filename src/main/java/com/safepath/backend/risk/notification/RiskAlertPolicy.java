package com.safepath.backend.risk.notification;

import com.safepath.backend.risk.entity.RiskEvent;

import org.springframework.stereotype.Component;

/**
 * Decides when a risk event alerts the member's guardians. Only this class changes when
 * the alert policy changes; RiskService just reports what happened to the event.
 */
@Component
public class RiskAlertPolicy {
    static final String CONFIRMED = "CONFIRMED";

    private final RiskEventNotifications notifications;

    public RiskAlertPolicy(RiskEventNotifications notifications) {
        this.notifications = notifications;
    }

    /**
     * A new event is only suspected (PENDING): the member is asked to confirm it first.
     * The guardian alert for this stage is still undecided, so nothing is sent yet.
     */
    public void onCreated(RiskEvent event) {
        // Intentionally empty until the suspected-stage alert policy is decided.
    }

    /** Guardians are alerted once, when the event first becomes CONFIRMED. */
    public void onStatusChanged(RiskEvent event, String previousStatus) {
        if (CONFIRMED.equals(event.getStatus()) && !CONFIRMED.equals(previousStatus)) {
            notifications.afterCommit(event.getUserId(), event.getId(), event.getEventType());
        }
    }
}
