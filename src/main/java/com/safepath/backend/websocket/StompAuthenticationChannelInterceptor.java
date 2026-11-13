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

/** Authenticates a STOMP connection once and keeps the verified user on its session. */
@Component
public class StompAuthenticationChannelInterceptor implements ChannelInterceptor {
    private final FirebaseTokenService firebaseTokenService;
    private final UserService userService;

    public StompAuthenticationChannelInterceptor(FirebaseTokenService firebaseTokenService, UserService userService) {
        this.firebaseTokenService = firebaseTokenService;
        this.userService = userService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (StompCommand.CONNECT != accessor.getCommand()) return message;

        String authorization = accessor.getFirstNativeHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BadCredentialsException("WebSocket Authorization: Bearer <Firebase ID token> header is required");
        }

        String firebaseUid = firebaseTokenService.verifyIdToken(authorization.substring("Bearer ".length()).trim());
        Long userId = userService.findUserIdByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new AccessDeniedException("USER_NOT_REGISTERED"));
        accessor.setUser(UsernamePasswordAuthenticationToken.authenticated(userId, null, null));
        return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
    }
}
