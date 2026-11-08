package com.safepath.backend.websocket;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryChannelRegistryTest {

    @Test
    void opensChannelForUserAndGuardians() {
        InMemoryChannelRegistry registry = new InMemoryChannelRegistry();

        String channelId = registry.open(1L, Set.of(2L, 3L));

        assertDoesNotThrow(() -> UUID.fromString(channelId));
        assertTrue(registry.exists(channelId));
        assertTrue(registry.isParticipant(channelId, 1L));
        assertTrue(registry.isParticipant(channelId, 2L));
        assertTrue(registry.isParticipant(channelId, 3L));
    }

    @Test
    void joinsOnlyAuthorizedParticipant() {
        InMemoryChannelRegistry registry = new InMemoryChannelRegistry();
        String channelId = registry.open(1L, Set.of(2L));

        registry.join(channelId, 2L, "session-1");

        assertTrue(registry.isSessionJoined(channelId, "session-1"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.join(channelId, 99L, "session-2"));
    }

    @Test
    void leavingRemovesOnlyWebSocketSession() {
        InMemoryChannelRegistry registry = new InMemoryChannelRegistry();
        String channelId = registry.open(1L, Set.of(2L));
        registry.join(channelId, 1L, "session-1");

        registry.leaveAll("session-1");

        assertTrue(registry.exists(channelId));
        assertFalse(registry.isSessionJoined(channelId, "session-1"));
        assertTrue(registry.isParticipant(channelId, 1L));
    }

    @Test
    void closeRemovesChannelAndSessions() {
        InMemoryChannelRegistry registry = new InMemoryChannelRegistry();
        String channelId = registry.open(1L, Set.of(2L));
        registry.join(channelId, 1L, "session-1");

        registry.close(channelId);

        assertFalse(registry.exists(channelId));
        assertFalse(registry.isSessionJoined(channelId, "session-1"));
    }
}
