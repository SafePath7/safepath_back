package com.safepath.backend.websocket;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

class WebSocketSessionEventListenerTest {
    @Test
    void removesSessionOnDisconnect() {
        InMemoryChannelRegistry registry = mock(InMemoryChannelRegistry.class);
        WebSocketSessionEventListener listener = new WebSocketSessionEventListener(registry);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId("session-1");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        listener.handleDisconnect(new SessionDisconnectEvent(this, message, "session-1", null));

        verify(registry).leaveAll("session-1");
    }
}
