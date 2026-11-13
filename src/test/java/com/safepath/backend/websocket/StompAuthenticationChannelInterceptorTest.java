package com.safepath.backend.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.user.UserService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

class StompAuthenticationChannelInterceptorTest {
    private final FirebaseTokenService tokens = org.mockito.Mockito.mock(FirebaseTokenService.class);
    private final UserService users = org.mockito.Mockito.mock(UserService.class);
    private final StompAuthenticationChannelInterceptor interceptor = new StompAuthenticationChannelInterceptor(tokens, users);

    @Test
    void bindsVerifiedUserToConnectSession() {
        when(tokens.verifyIdToken("firebase-token")).thenReturn("firebase-uid");
        when(users.findUserIdByFirebaseUid("firebase-uid")).thenReturn(Optional.of(42L));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer firebase-token");

        Message<?> authenticated = interceptor.preSend(message(accessor), null);
        Authentication principal = (Authentication) StompHeaderAccessor.wrap(authenticated).getUser();
        assertEquals(42L, principal.getPrincipal());
    }

    @Test
    void rejectsConnectWithoutBearerToken() {
        assertThrows(BadCredentialsException.class,
                () -> interceptor.preSend(message(StompHeaderAccessor.create(StompCommand.CONNECT)), null));
    }

    private Message<byte[]> message(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
