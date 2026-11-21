package com.safepath.backend.voice;

import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.config.FirebaseConfig;
import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import com.safepath.backend.voice.storage.VoiceAudioStorage;
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
import tools.jackson.databind.json.JsonMapper;

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
    @Autowired JdbcTemplate jdbc;
    @Autowired VoiceProfileRegistrationService registration;
    @Autowired(required = false) VoiceAudioStorage storage;
    @Value("${SAFEPATH_AI_INTERNAL_KEY:}") String internalKey;

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

    @Test
    @EnabledIfEnvironmentVariable(named = "SAFEPATH_VOICE_TEST_DB_URL",
            matches = "jdbc:postgresql://127\\.0\\.0\\.1:5433/safepath_voice_dev")
    void realRegistrationAndUpdatePersistOwnerFeaturesAndReplaceRecordings() throws Exception {
        assertEquals("safepath_voice_dev", jdbc.queryForObject("SELECT current_database()", String.class));
        String fixture = "voice-http-" + UUID.randomUUID();
        long userId = jdbc.queryForObject("""
                INSERT INTO users (firebase_uid,name,email,phone_hash,phone_encrypted)
                VALUES (?,'voice HTTP test',?,?,'synthetic') RETURNING id
                """, Long.class, fixture, fixture + "@example.test", fixture);
        when(tokens.verifyIdToken("synthetic-voice-http-token")).thenReturn(fixture);
        try (var client = HttpClient.newHttpClient()) {
            assertEquals(404, readFeatures(client, "synthetic-voice-http-token", internalKey).statusCode());
            assertEquals(404, write(client, "PUT", false));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM voice_profiles WHERE user_id=?", Integer.class, userId));
            assertEquals(201, write(client, "POST", true));
            assertFeatures(client, 1, 0, "http-create");
            var before = jdbc.queryForMap("""
                    SELECT cardinality(speaker_embedding) AS dimensions, baseline_details::text AS details,
                           audio_file_url, urgent_audio_file_url, consented_at
                    FROM voice_profiles WHERE user_id=?
                    """, userId);
            assertEquals(192, before.get("dimensions"));
            assertEquals("{\"sample\": true}", before.get("details"));
            String oldCalm = (String) before.get("audio_file_url");
            String oldUrgent = (String) before.get("urgent_audio_file_url");
            assertArrayEquals(wave(), storage.read(oldCalm));
            assertArrayEquals(wave(), storage.read(oldUrgent));
            assertEquals(409, write(client, "POST", false));
            assertEquals(oldCalm, jdbc.queryForObject("SELECT audio_file_url FROM voice_profiles WHERE user_id=?", String.class, userId));
            assertEquals(204, write(client, "PUT", false));
            assertFeatures(client, 0, 1, "http-update");
            assertEquals(401, readFeatures(client, null, internalKey).statusCode());
            assertEquals(403, readFeatures(client, "synthetic-voice-http-token", null).statusCode());
            var after = jdbc.queryForMap("SELECT audio_file_url, urgent_audio_file_url, consented_at FROM voice_profiles WHERE user_id=?", userId);
            assertEquals(before.get("consented_at"), after.get("consented_at"));
            assertNull(after.get("urgent_audio_file_url"));
            assertArrayEquals(wave(), storage.read((String) after.get("audio_file_url")));
            assertFalse(storage.exists(oldCalm));
            assertFalse(storage.exists(oldUrgent));
        } finally {
            if (jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM voice_profiles WHERE user_id=?)", Boolean.class, userId)) {
                registration.delete(userId);
            }
            jdbc.update("DELETE FROM users WHERE id=? AND firebase_uid=?", userId, fixture);
        }
    }

    private void assertFeatures(HttpClient client, int first, int second, String model) throws Exception {
        var response = readFeatures(client, "synthetic-voice-http-token", internalKey);
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var json = new JsonMapper().readTree(response.body());
        assertEquals(5, json.size());
        assertEquals(192, json.required("speakerEmbedding").size());
        assertEquals(first, json.required("speakerEmbedding").get(0).intValue());
        assertEquals(second, json.required("speakerEmbedding").get(1).intValue());
        assertEquals(-20, json.required("baselineVolume").doubleValue());
        assertEquals(180, json.required("baselinePitch").doubleValue());
        assertEquals(model, json.required("modelVersion").asString());
        assertTrue(json.required("baselineDetails").required("sample").booleanValue());
    }

    private HttpResponse<String> readFeatures(HttpClient client, String token, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/voice-profiles/me/features"))
                .timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("X-Internal-Key", key);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private int write(HttpClient client, String method, boolean urgent) throws Exception {
        var parts = new java.util.ArrayList<HttpRequest.BodyPublisher>();
        boolean update = "PUT".equals(method);
        parts.add(HttpRequest.BodyPublishers.ofString("--" + BOUNDARY
                + "\r\nContent-Disposition: form-data; name=\"profile\"\r\nContent-Type: application/json\r\n\r\n"
                + "{\"speakerEmbedding\":[" + (update ? "0,1" : "1,0") + ",0".repeat(190)
                + "],\"baselineVolume\":-20,\"baselinePitch\":180,\"modelVersion\":\""
                + (update ? "http-update" : "http-create") + "\",\"baselineDetails\":{\"sample\":true}}\r\n"));
        for (String name : urgent ? java.util.List.of("file", "urgentFile") : java.util.List.of("file")) {
            parts.add(HttpRequest.BodyPublishers.ofString("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\""
                    + name + "\"; filename=\"fixture.wav\"\r\nContent-Type: audio/wav\r\n\r\n"));
            parts.add(HttpRequest.BodyPublishers.ofByteArray(wave()));
            parts.add(HttpRequest.BodyPublishers.ofString("\r\n"));
        }
        parts.add(HttpRequest.BodyPublishers.ofString("--" + BOUNDARY + "--\r\n"));
        String path = update ? "/api/voice-profiles/me" : "/api/voice-profiles";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer synthetic-voice-http-token")
                .header("X-Internal-Key", internalKey)
                .method(method, HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new))).build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static byte[] wave() {
        return ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46464952).putInt(38).putInt(0x45564157)
                .putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
                .putInt(0x61746164).putInt(2).array();
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
