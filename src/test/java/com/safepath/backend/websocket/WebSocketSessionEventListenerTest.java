
package com.safepath.backend.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import static org.mockito.Mockito.*;

class WebSocketSessionEventListenerTest {

    @Test
    void 채널_구독_이벤트_테스트() {
        InMemoryChannelRegistry registry =
                mock(InMemoryChannelRegistry.class);

        WebSocketSessionEventListener listener =
                new WebSocketSessionEventListener(registry);

        StompHeaderAccessor accessor =
                StompHeaderAccessor.create(
                        org.springframework.messaging.simp.stomp.StompCommand.SUBSCRIBE
                );

        accessor.setSessionId("session-1");
        accessor.setDestination("/topic/connections/abc123");

        Message<byte[]> message = MessageBuilder.createMessage(
                new byte[0],
                accessor.getMessageHeaders()
        );

        SessionSubscribeEvent event =
                new SessionSubscribeEvent(this, message);

        listener.handleSubscribe(event);

        verify(registry).join("abc123", "session-1");
    }

    @Test
    void 연결_종료_이벤트_테스트() {
        InMemoryChannelRegistry registry =
                mock(InMemoryChannelRegistry.class);

        WebSocketSessionEventListener listener =
                new WebSocketSessionEventListener(registry);

        StompHeaderAccessor accessor =
                StompHeaderAccessor.create(
                        org.springframework.messaging.simp.stomp.StompCommand.DISCONNECT
                );

        accessor.setSessionId("session-1");

        Message<byte[]> message = MessageBuilder.createMessage(
                new byte[0],
                accessor.getMessageHeaders()
        );

        SessionDisconnectEvent event =
                new SessionDisconnectEvent(
                        this, message, "session-1", null
                );

        listener.handleDisconnect(event);

        verify(registry).leaveAll("session-1");
    }
}
