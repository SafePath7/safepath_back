package com.safepath.backend.websocket;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.config.FirebaseConfig;
import com.safepath.backend.user.UserService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketLocationIntegrationTest {
    @LocalServerPort int port;
    @Autowired InMemoryChannelRegistry channels;
    @MockitoBean FirebaseConfig firebaseConfig;
    @MockitoBean FirebaseTokenService tokens;
    @MockitoBean UserService users;

    @Test
    void relaysUserLocationToGuardianSubscribedToTheSameChannel() throws Exception {
        when(tokens.verifyIdToken("user-token")).thenReturn("user-uid");
        when(tokens.verifyIdToken("guardian-token")).thenReturn("guardian-uid");
        when(users.findUserIdByFirebaseUid("user-uid")).thenReturn(Optional.of(1L));
        when(users.findUserIdByFirebaseUid("guardian-uid")).thenReturn(Optional.of(2L));
        String channelId = channels.open(1L, Set.of(2L));

        StompSocket user = connect();
        StompSocket guardian = connect();
        try {
            user.send(connectFrame("user-token"));
            user.await("CONNECTED");
            guardian.send(connectFrame("guardian-token"));
            guardian.await("CONNECTED");

            guardian.send(subscribeFrame(channelId, "guardian-subscription"));
            user.send(subscribeFrame(channelId, "user-subscription"));
            Thread.sleep(250);

            user.send(locationFrame(channelId));
            String message = guardian.await("MESSAGE");

            assertTrue(message.contains("/topic/connections/" + channelId + "/locations"));
            assertTrue(message.contains("\"senderId\":\"1\""));
            assertTrue(message.contains("\"latitude\":37.5665"));
        } finally {
            user.close();
            guardian.close();
            channels.close(channelId);
        }
    }

    private StompSocket connect() throws Exception {
        FrameListener listener = new FrameListener();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), listener)
                .get(5, TimeUnit.SECONDS);
        return new StompSocket(socket, listener);
    }

    private static String connectFrame(String token) {
        return "CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer " + token + "\n\n\0";
    }

    private static String subscribeFrame(String channelId, String id) {
        return "SUBSCRIBE\nid:" + id + "\ndestination:/topic/connections/" + channelId
                + "/locations\n\n\0";
    }

    private static String locationFrame(String channelId) {
        return "SEND\ndestination:/app/connections/" + channelId + "/locations\ncontent-type:application/json\n\n"
                + "{\"latitude\":37.5665,\"longitude\":126.9780,\"accuracyMeters\":8.5,\"capturedAt\":\""
                + Instant.parse("2026-11-14T12:00:00Z") + "\"}\0";
    }

    private record StompSocket(WebSocket socket, FrameListener listener) {
        void send(String frame) throws Exception {
            socket.sendText(frame, true).get(5, TimeUnit.SECONDS);
        }

        String await(String command) throws Exception {
            return listener.await(command);
        }

        void close() throws Exception {
            if (!socket.isOutputClosed()) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static final class FrameListener implements WebSocket.Listener {
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                frames.add(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        String await(String command) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            List<String> observed = new ArrayList<>();
            while (System.nanoTime() < deadline) {
                String frame = frames.poll(5, TimeUnit.SECONDS);
                if (frame != null && frame.startsWith(command)) return frame;
                if (frame != null) observed.add(frame);
            }
            throw new AssertionError("Did not receive STOMP " + command + " frame; received: " + observed);
        }
    }
}
