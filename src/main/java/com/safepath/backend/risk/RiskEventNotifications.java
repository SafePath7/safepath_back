package com.safepath.backend.risk;

import com.safepath.backend.risk.RiskModels.RiskEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class RiskEventNotifications {
    private static final Logger log = LoggerFactory.getLogger(RiskEventNotifications.class);
    private final ObjectProvider<RiskEventUpdateNotifier> notifier;

    public RiskEventNotifications(ObjectProvider<RiskEventUpdateNotifier> notifier) {
        this.notifier = notifier;
    }

    public void afterCommit(long userId, RiskEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Risk notification requires an active database transaction");
        }
        // One immutable callback per successful mutation, even when an outer transaction
        // groups several creates/updates. Rollbacks never invoke afterCommit.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    RiskEventUpdateNotifier delegate = notifier.getIfAvailable();
                    if (delegate == null) {
                        log.warn("Risk notification not connected: eventId={}, userId={}, eventType={}",
                                event.id(), userId, event.eventType());
                        return;
                    }
                    delegate.notifyRiskEventUpdate(userId, event.eventType());
                } catch (RuntimeException exception) {
                    // The write is already committed. Do not report a failed write or
                    // prevent callbacks for the other mutations in this transaction.
                    log.error("Risk notification failed after commit: eventId={}, userId={}, eventType={}",
                            event.id(), userId, event.eventType(), exception);
                }
            }
        });
    }
}
