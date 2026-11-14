package com.safepath.backend.risk;

import com.safepath.backend.notification.NotificationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Delegates recipient selection and delivery to the existing notification module. */
@Component
public class TeamRiskEventUpdateNotifier implements RiskEventUpdateNotifier {
    private static final Logger log = LoggerFactory.getLogger(TeamRiskEventUpdateNotifier.class);
    private final NotificationService notifications;

    public TeamRiskEventUpdateNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void notifyRiskEventUpdate(long userId, String eventType) {
        // afterCommit still has the completed transaction's resources bound to the
        // thread. A new transaction gives the teammate's JDBC lookup fresh resources.
        var result = notifications.notifyRiskEventUpdate(userId, eventType);
        if (result != null && result.failureCount() > 0) {
            log.warn("Risk notification delivery incomplete: userId={}, eventType={}, successCount={}, failureCount={}",
                    userId, eventType, result.successCount(), result.failureCount());
        }
    }
}
