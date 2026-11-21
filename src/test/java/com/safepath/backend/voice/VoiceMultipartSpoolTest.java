package com.safepath.backend.voice;

import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.config.FirebaseConfig;
import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(VoiceMultipartSpoolTest.ProbeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VoiceMultipartSpoolTest {
    private static final String PROBE_PATH = "/__test/voice-multipart-spool";
    private static final String BOUNDARY = "safepath-spool-fixture";
    private static final byte[] PAYLOAD = new byte[4096];

    static {
        Arrays.fill(PAYLOAD, (byte) 'x');
    }

    @TempDir static Path spool;
    @LocalServerPort int port;
    @Autowired MultipartConfigElement config;
    @Autowired MultipartResolver resolver;
    @MockitoBean FirebaseConfig firebaseConfig;
    @MockitoBean FirebaseTokenService tokens;

    @DynamicPropertySource
    static void isolateSpool(DynamicPropertyRegistry registry) {
        registry.add("spring.servlet.multipart.location", () -> spool.toString());
    }

    @Test
    void realServletRequestSpoolsPlaintextAndRemovesItAfterResponse() throws Exception {
        assertInstanceOf(StandardServletMultipartResolver.class, resolver);
        assertEquals(10L * 1024 * 1024, config.getMaxFileSize());
        assertEquals(11L * 1024 * 1024, config.getMaxRequestSize());
        assertEquals(0, config.getFileSizeThreshold());
        when(tokens.verifyIdToken("synthetic-spool-token")).thenReturn("synthetic-spool-user");
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"synthetic.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n"
                + new String(PAYLOAD, StandardCharsets.US_ASCII) + "\r\n--" + BOUNDARY + "--\r\n";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PROBE_PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer synthetic-spool-token")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("true", response.body());
        }
        assertTimeout(Duration.ofSeconds(5), () -> {
            long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
            do {
                try (var paths = Files.list(spool)) {
                    if (paths.findAny().isEmpty()) return;
                }
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            fail("The multipart spool still contains a file after the request.");
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        SpoolProbe spoolProbe() { return new SpoolProbe(); }
    }

    @TestComponent
    @RestController
    static class SpoolProbe {
        @PostMapping(path = PROBE_PATH, consumes = "multipart/form-data")
        String spoolPresent(@RequestPart("file") MultipartFile file) throws IOException {
            assertArrayEquals(PAYLOAD, file.getBytes());
            try (var paths = Files.list(spool)) {
                for (Path path : paths.toList()) {
                    if (Files.isRegularFile(path) && Arrays.equals(PAYLOAD, Files.readAllBytes(path))) return "true";
                }
            }
            return "false";
        }
    }
}
