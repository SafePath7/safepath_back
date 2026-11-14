package com.safepath.backend.websocket;

import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.user.UserService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Authenticates a STOMP connection once and keeps the verified user on its session. */
@Component
public class StompAuthenticationChannelInterceptor implements ChannelInterceptor {
    private static final String LOCATION_TOPIC_PREFIX = "/topic/connections/";
    private static final String LOCATION_TOPIC_SUFFIX = "/locations";
    static final String AUTHENTICATED_USER_ID =
            StompAuthenticationChannelInterceptor.class.getName() + ".authenticatedUserId";

    private final FirebaseTokenService firebaseTokenService;
    private final UserService userService;
    private final InMemoryChannelRegistry channelRegistry;

    public StompAuthenticationChannelInterceptor(
            FirebaseTokenService firebaseTokenService,
            UserService userService,
            InMemoryChannelRegistry channelRegistry) {
        this.firebaseTokenService = firebaseTokenService;
        this.userService = userService;
        this.channelRegistry = channelRegistry;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (StompCommand.CONNECT == accessor.getCommand()) {
            return authenticateConnect(message, accessor);
        }
        if (StompCommand.SUBSCRIBE == accessor.getCommand()) {
            authorizeLocationSubscription(accessor);
        }
        return message;
    }

    private Message<?> authenticateConnect(Message<?> message, StompHeaderAccessor accessor) {

        String authorization = accessor.getFirstNativeHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BadCredentialsException("WebSocket Authorization: Bearer <Firebase ID token> header is required");
        }

        String firebaseUid = firebaseTokenService.verifyIdToken(authorization.substring("Bearer ".length()).trim());
        Long userId = userService.findUserIdByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new AccessDeniedException("USER_NOT_REGISTERED"));
        accessor.setUser(UsernamePasswordAuthenticationToken.authenticated(userId, null, null));
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            throw new BadCredentialsException("WebSocket session attributes are required");
        }
        sessionAttributes.put(AUTHENTICATED_USER_ID, userId);
        return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
    }

    private void authorizeLocationSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(LOCATION_TOPIC_PREFIX)
                || !destination.endsWith(LOCATION_TOPIC_SUFFIX)) {
            return;
        }
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        Object authenticatedUserId = sessionAttributes == null ? null : sessionAttributes.get(AUTHENTICATED_USER_ID);
        if (!(authenticatedUserId instanceof Long participantId) || accessor.getSessionId() == null) {
            throw new BadCredentialsException("Authenticated WebSocket session is required");
        }
        String channelId = destination.substring(
                LOCATION_TOPIC_PREFIX.length(), destination.length() - LOCATION_TOPIC_SUFFIX.length());
        if (channelId.isBlank()) {
            throw new IllegalArgumentException("WebSocket channel ID is required");
        }
        channelRegistry.join(channelId, participantId, accessor.getSessionId());
    }
}
