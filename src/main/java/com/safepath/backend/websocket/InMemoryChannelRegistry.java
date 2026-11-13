package com.safepath.backend.websocket;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryChannelRegistry {

    private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<>();

    public String open(Long userId, Set<Long> guardianIds) {
        if (userId == null || guardianIds == null) {
            throw new IllegalArgumentException("사용자와 보호자 정보는 필수입니다.");
        }

        Set<Long> participantIds = ConcurrentHashMap.newKeySet();
        participantIds.add(userId);
        participantIds.addAll(guardianIds);

        String channelId = UUID.randomUUID().toString();
        channels.put(channelId, new Channel(userId, participantIds));
        return channelId;
    }

    public boolean exists(String channelId) {
        return channels.containsKey(channelId);
    }

    public boolean isParticipant(String channelId, Long participantId) {
        Channel channel = channels.get(channelId);
        return channel != null && participantId != null && channel.participantIds().contains(participantId);
    }

    public boolean isLocationPublisher(String channelId, Long participantId) {
        Channel channel = channels.get(channelId);
        return channel != null && participantId != null && channel.userId().equals(participantId);
    }

    public void join(String channelId, Long participantId, String sessionId) {
        Channel channel = channels.get(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("존재하지 않는 WebSocket 채널입니다.");
        }
        if (!channel.participantIds().contains(participantId)) {
            throw new IllegalArgumentException("채널 참여 권한이 없습니다.");
        }
        channel.sessions().put(sessionId, participantId);
    }

    public boolean isSessionJoined(String channelId, String sessionId) {
        Channel channel = channels.get(channelId);
        return channel != null && channel.sessions().containsKey(sessionId);
    }

    public Optional<Long> participantIdForSession(String channelId, String sessionId) {
        Channel channel = channels.get(channelId);
        return channel == null ? Optional.empty() : Optional.ofNullable(channel.sessions().get(sessionId));
    }

    public void leaveAll(String sessionId) {
        channels.forEach((channelId, channel) -> channel.sessions().remove(sessionId));
    }

    public void close(String channelId) {
        channels.remove(channelId);
    }

    private record Channel(
            Long userId,
            Set<Long> participantIds,
            ConcurrentMap<String, Long> sessions
    ) {
        private Channel(Long userId, Set<Long> participantIds) {
            this(userId, Set.copyOf(participantIds), new ConcurrentHashMap<>());
        }
    }
}
