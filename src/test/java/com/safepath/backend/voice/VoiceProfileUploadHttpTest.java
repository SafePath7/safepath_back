package com.safepath.backend.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.safepath.backend.auth.FirebaseAuthenticationFilter;
import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.common.auth.CurrentUser;
import com.safepath.backend.common.auth.FirebaseAdminRoleLookup;
import com.safepath.backend.config.SecurityConfig;
import com.safepath.backend.risk.config.EventMediaUploadConfiguration;
import com.safepath.backend.user.UserService;
import com.safepath.backend.voice.exception.VoiceProfileExceptionHandler;
import com.safepath.backend.voice.exception.VoiceProfileUploadExceptionResolver;
import com.safepath.backend.voice.security.InternalAiKeyVerifier;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = VoiceProfileUploadHttpTest.WebConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "spring.profiles.active=upload-http-test", "server.address=127.0.0.1",
            "SAFEPATH_AI_INTERNAL_KEY=synthetic-http-test-key"
        })
class VoiceProfileUploadHttpTest {
    private static final int MEBIBYTE = 1024 * 1024;
    private static final String BOUNDARY = "voice-http-test-boundary";
    private static final String KEY = "synthetic-http-test-key";
    @TempDir static Path spool;
    @LocalServerPort int port;
    @MockitoBean VoiceProfileRegistrationService registration;
    @MockitoBean VoiceProfileService profiles;
    @MockitoBean FirebaseTokenService tokens;
    @MockitoBean FirebaseAdminRoleLookup roles;
    @MockitoBean UserService users;

    @DynamicPropertySource
    static void multipartLocation(DynamicPropertyRegistry properties) {
        properties.add("spring.servlet.multipart.location", () -> spool.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT"})
    void parserRejectsFileOverTenMebibytes(String method) throws Exception {
        when(tokens.verifyIdToken("token")).thenReturn("uid");
        assertThat(upload(method, KEY, 10 * MEBIBYTE + 1)).isEqualTo(413);
        verifyNoInteractions(registration);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT"})
    void parserRejectsAggregateLimitFromContentLength(String method) throws Exception {
        when(tokens.verifyIdToken("token")).thenReturn("uid");
        assertThat(responseBeforeBody(method, KEY, 25 * MEBIBYTE + 1)).startsWith("HTTP/1.1 413 ");
        verifyNoInteractions(registration);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT"})
    void unauthorizedOversizeRequestNeverReachesMultipartParser(String method) throws Exception {
        when(tokens.verifyIdToken("token")).thenReturn("uid");
        assertThat(responseBeforeBody(method, "invalid", 10 * MEBIBYTE + 1)).startsWith("HTTP/1.1 403 ");
        verifyNoInteractions(registration, users);
        try (var files = Files.list(spool)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    private String responseBeforeBody(String method, String key, int contentLength) throws Exception {
        try (var socket = new Socket("127.0.0.1", port);
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
            socket.setSoTimeout(5000);
            var headers = """
                    %s %s HTTP/1.1
                    Host: 127.0.0.1
                    Authorization: Bearer token
                    X-Internal-Key: %s
                    Content-Type: multipart/form-data; boundary=%s
                    Content-Length: %d
                    Connection: close

                    """.formatted(method, path(method), key, BOUNDARY, contentLength).replace("\n", "\r\n");
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            return reader.readLine();
        }
    }

    private int upload(String method, String key, int... sizes) throws Exception {
        var parts = new java.util.ArrayList<HttpRequest.BodyPublisher>();
        for (int index = 0; index < sizes.length; index++) {
            parts.add(HttpRequest.BodyPublishers.ofString("--" + BOUNDARY
                    + "\r\nContent-Disposition: form-data; name=\"file" + index
                    + "\"; filename=\"fixture.wav\"\r\nContent-Type: audio/wav\r\n\r\n"));
            parts.add(HttpRequest.BodyPublishers.ofByteArray(new byte[sizes[index]]));
            parts.add(HttpRequest.BodyPublishers.ofString("\r\n"));
        }
        parts.add(HttpRequest.BodyPublishers.ofString("--" + BOUNDARY + "--\r\n"));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path(method)))
                .header("Authorization", "Bearer token")
                .header("X-Internal-Key", key)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .timeout(Duration.ofSeconds(15)).expectContinue(true)
                .method(method, HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new))).build();
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    private static String path(String method) {
        return "PUT".equals(method) ? "/api/voice-profiles/me" : "/api/voice-profiles";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
    })
    @Import({VoiceProfileController.class, VoiceProfileFeatureService.class, VoiceProfileExceptionHandler.class,
            VoiceProfileUploadExceptionResolver.class, CurrentUser.class, InternalAiKeyVerifier.class,
            FirebaseAuthenticationFilter.class, SecurityConfig.class, EventMediaUploadConfiguration.class})
    static class WebConfiguration {}
}
