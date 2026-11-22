package com.safepath.backend.risk;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import com.safepath.backend.common.auth.CurrentUser.Actor;
import com.safepath.backend.risk.dto.EventRequest;
import com.safepath.backend.risk.entity.RiskEvent;
import com.safepath.backend.risk.entity.RiskSessionSnapshot;
import com.safepath.backend.risk.notification.RiskAlertPolicy;
import com.safepath.backend.risk.notification.RiskEventNotifications;
import com.safepath.backend.risk.repository.RiskEventRepository;
import com.safepath.backend.risk.service.RiskEventAccess;
import com.safepath.backend.risk.service.RiskService;
import com.safepath.backend.risk.service.RiskSessionBridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Guardians are alerted only when an event becomes CONFIRMED, never on creation. */
class RiskEventAlertTests {
    static final long OWNER = 7L;
    static final long EVENT_ID = 42L;

    final RiskEventRepository events = mock(RiskEventRepository.class);
    final RiskEventNotifications notifications = mock(RiskEventNotifications.class);
    final RiskSessionBridge sessions = mock(RiskSessionBridge.class);
    final RiskService service = new RiskService(events, new RiskAlertPolicy(notifications), sessions,
            mock(RiskEventAccess.class));
    final Actor owner = new Actor(OWNER, false);
    final RiskSessionSnapshot session = mock(RiskSessionSnapshot.class);

    @BeforeEach
    void setup() {
        when(session.getUserId()).thenReturn(OWNER);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(events.saveAndFlush(any(RiskEvent.class))).thenAnswer(call -> {
            RiskEvent saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", EVENT_ID);
            return saved;
        });
    }

    @Test
    void creatingAPendingEventSendsNoAlertAndReturnsItsId() {
        when(sessions.ensurePersistedOwner(eq(owner), any())).thenReturn(session);

        var response = service.createEvent(owner,
                new EventRequest(UUID.randomUUID(), "scream", "비명 감지", Instant.now()));

        assertThat(response.id()).isEqualTo(EVENT_ID);
        assertThat(response.status()).isEqualTo("PENDING");
        verifyNoInteractions(notifications);
    }

    @Test
    void confirmingAPendingEventAlertsGuardiansOnce() {
        givenEventWithStatus("PENDING");

        service.updateEvent(owner, EVENT_ID, "CONFIRMED");

        verify(notifications).afterCommit(OWNER, EVENT_ID, "scream");
    }

    @Test
    void dismissingAnEventSendsNoAlert() {
        givenEventWithStatus("PENDING");

        var response = service.updateEvent(owner, EVENT_ID, "DISMISSED");

        assertThat(response.status()).isEqualTo("DISMISSED");
        verifyNoInteractions(notifications);
    }

    @Test
    void confirmingAnAlreadyConfirmedEventDoesNotAlertAgain() {
        givenEventWithStatus("CONFIRMED");

        service.updateEvent(owner, EVENT_ID, "CONFIRMED");

        verifyNoInteractions(notifications);
    }

    private void givenEventWithStatus(String status) {
        RiskEvent event = new RiskEvent(session, "scream", "비명 감지",
                LocalDateTime.of(2026, 11, 22, 10, 0));
        ReflectionTestUtils.setField(event, "id", EVENT_ID);
        ReflectionTestUtils.setField(event, "status", status);
        when(events.findForUpdateById(EVENT_ID)).thenReturn(Optional.of(event));
    }
}
