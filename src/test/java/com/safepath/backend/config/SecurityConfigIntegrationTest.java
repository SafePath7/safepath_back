package com.safepath.backend.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.safepath.backend.auth.FirebaseAuthenticationFilter;
import com.safepath.backend.auth.FirebaseTokenService;
import jakarta.servlet.ServletContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SecurityConfigIntegrationTest.TestEndpoints.class)
class SecurityConfigIntegrationTest {
    @LocalServerPort int port;
    @MockitoBean FirebaseTokenService tokens;
    @Autowired ServletContext servletContext;
    @Autowired FilterChainProxy securityChain;
    private final HttpClient client = HttpClient.newHttpClient();

    @TestConfiguration
    static class TestEndpoints {
        @Bean TestController testController() { return new TestController(); }
    }

    @RestController
    static class TestController {
        @GetMapping("/test/secured")
        String secured(@AuthenticationPrincipal String uid) { return uid; }
        @GetMapping("/test/denied")
        String denied() { throw new AccessDeniedException("denied"); }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void missingInvalidValidAndForbiddenRequests() throws Exception {
        when(tokens.verifyIdToken("invalid")).thenThrow(new BadCredentialsException("invalid"));
        when(tokens.verifyIdToken("valid")).thenReturn("firebase-uid");
        assertEquals(401, get("/test/secured", null).statusCode());
        assertEquals(401, get("/test/secured", "invalid").statusCode());
        var success = get("/test/secured", "valid");
        assertEquals(200, success.statusCode());
        assertEquals("firebase-uid", success.body());
        assertEquals(403, get("/test/denied", "valid").statusCode());
        verify(tokens, times(2)).verifyIdToken("valid");
    }

    @Test
    void filterRunsOnlyInsideSecurityChain() {
        assertEquals(0, servletContext.getFilterRegistrations().values().stream()
                .filter(registration -> FirebaseAuthenticationFilter.class.getName().equals(registration.getClassName())).count());
        assertEquals(1, securityChain.getFilters("/test/secured").stream()
                .filter(FirebaseAuthenticationFilter.class::isInstance).count());
    }

    @Test
    void loginAndWebSocketHandshakeRemainPublic() throws Exception {
        var login = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        // Login controller returns 401 for a missing token; the request reaches it without invoking verification.
        assertEquals(401, client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode());
        verifyNoInteractions(tokens);
        CompletableFuture<String> stompResponse = new CompletableFuture<>();
        WebSocket socket = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    private final StringBuilder message = new StringBuilder();
                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        message.append(data);
                        if (last) stompResponse.complete(message.toString());
                        ws.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);
        socket.sendText("CONNECT\naccept-version:1.2\nhost:localhost\n\n\0", true).get(5, TimeUnit.SECONDS);
        assertTrue(stompResponse.get(5, TimeUnit.SECONDS).startsWith("CONNECTED"));
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        verifyNoInteractions(tokens);
    }
}
