package com.safepath.backend.guardian;

import com.safepath.backend.notification.NotificationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calls the notification module's guardian-request push for a saved registration.
 * Recipient lookup and delivery stay in the teammate's NotificationService.
 */
@Component
public class GuardianRequestNotifier {
    private static final Logger log = LoggerFactory.getLogger(GuardianRequestNotifier.class);
    private final NotificationService notifications;

    public GuardianRequestNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void notifyGuardianRequested(long requesterId, long guardianId) {
        // afterCommit still has the completed transaction's resources bound to the
        // thread. A new transaction gives the teammate's JDBC lookup fresh resources.
        var result = notifications.sendGuardianRequestNotification(requesterId, guardianId);
        if (result != null && result.failureCount() > 0) {
            log.warn("Guardian request notification delivery failed: requesterId={}, guardianId={}",
                    requesterId, guardianId);
        }
    }
}
