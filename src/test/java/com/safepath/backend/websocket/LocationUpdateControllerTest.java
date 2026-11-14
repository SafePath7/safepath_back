package com.safepath.backend.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.safepath.backend.websocket.dto.LocationUpdateRequest;
import com.safepath.backend.websocket.dto.LocationUpdateResponse;
import java.time.Instant;
import java.util.Set;
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

        LocationUpdateResponse response = new LocationUpdateController(registry).relay(
                channelId,
                new LocationUpdateRequest(37.5665, 126.9780, 8.5, Instant.parse("2026-11-14T12:00:00Z")),
                headers);

        assertEquals("1", response.senderId());
        assertEquals(37.5665, response.latitude());
    }

    @Test
    void rejectsGuardianLocationPublication() {
        InMemoryChannelRegistry registry = new InMemoryChannelRegistry();
        String channelId = registry.open(1L, Set.of(2L));
        registry.join(channelId, 2L, "guardian-session");
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        headers.setSessionId("guardian-session");

        assertThrows(IllegalArgumentException.class, () -> new LocationUpdateController(registry).relay(
                channelId,
                new LocationUpdateRequest(37.5665, 126.9780, null, Instant.now()),
                headers));
    }
}
