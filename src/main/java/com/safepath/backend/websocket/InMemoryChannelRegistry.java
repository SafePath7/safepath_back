package com.safepath.backend.websocket;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryChannelRegistry {

    private final ConcurrentMap<String, Set<String>> channelSessions = new ConcurrentHashMap<>();

    public String create() {
        String channelId = UUID.randomUUID().toString();
        channelSessions.put(channelId, ConcurrentHashMap.newKeySet());
        return channelId;
    }

    public boolean exists(String channelId) {
        return channelSessions.containsKey(channelId);
    }

    public void join(String channelId, String sessionId) {
        Set<String> sessions = channelSessions.get(channelId);
        if (sessions == null) {
            throw new IllegalArgumentException("존재하지 않는 WebSocket 채널입니다.");
        }
        sessions.add(sessionId);
    }

    public void leaveAll(String sessionId) {
        channelSessions.forEach((channelId, sessions) -> {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                channelSessions.remove(channelId, sessions);
            }
        });
    }
}
