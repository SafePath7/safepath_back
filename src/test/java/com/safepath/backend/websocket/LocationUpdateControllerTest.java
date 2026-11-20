package com.safepath.backend.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.model.SafetyStatus;
import com.safepath.backend.session.service.SafeReturnSessionService;
import com.safepath.backend.websocket.dto.LocationUpdateRequest;
import com.safepath.backend.websocket.dto.LocationUpdateResponse;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

class LocationUpdateControllerTest {
    @Test
    void relaysLocationWithAuthenticatedChannelUserAsSender() {
        InMemoryChannelRegistry registry = new InMemoryChannelRegistry();
        String channelId = registry.open(1L, Set.of(2L));
        registry.join(channelId, 1L, "user-session");
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        headers.setSessionId("user-session");

        var sessions = mock(SafeReturnSessionService.class);
        when(sessions.getSessionForOwnerChannel(channelId, 1L)).thenReturn(
                new ActiveSession(UUID.randomUUID(), 1L, Instant.now(), null, SafetyStatus.RISK_SUSPECTED));
        LocationUpdateResponse response = new LocationUpdateController(registry, sessions).relay(
                channelId,
                new LocationUpdateRequest(37.5665, 126.9780, 8.5, Instant.parse("2026-11-14T12:00:00Z")),
                headers);

        assertEquals("1", response.senderId());
        assertEquals(37.5665, response.latitude());
        assertEquals(SafetyStatus.RISK_SUSPECTED, response.safetyStatus());
    }

    @Test
    void rejectsGuardianLocationPublication() {
        InMemoryChannelRegistry registry = new InMemoryChannelRegistry();
        String channelId = registry.open(1L, Set.of(2L));
        registry.join(channelId, 2L, "guardian-session");
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        headers.setSessionId("guardian-session");

        assertThrows(IllegalArgumentException.class, () -> new LocationUpdateController(registry, mock(SafeReturnSessionService.class)).relay(
                channelId,
                new LocationUpdateRequest(37.5665, 126.9780, null, Instant.now()),
                headers));
    }
}
