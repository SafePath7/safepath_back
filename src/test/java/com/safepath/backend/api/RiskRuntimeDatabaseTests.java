package com.safepath.backend.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.safepath.backend.common.ApiException;
import com.safepath.backend.risk.EventMediaStorage;
import com.safepath.backend.security.FirebaseIdTokenVerifier;
import com.safepath.backend.security.FirebaseIdTokenVerifier.VerifiedIdentity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/** Real HTTP and committed transactions, isolated from public data by a fully migrated schema. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RiskRuntimeDatabaseTests {
    private static final String SCHEMA = "api_audit_" + UUID.randomUUID().toString().replace("-", "");
    private static final Path MEDIA_ROOT = temporaryDirectory();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final byte[] MEDIA_CONTENTS = {1, 2, 3, 4};

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.create-schemas", () -> true);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
        registry.add("safepath.media.root", MEDIA_ROOT::toString);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FirebaseIdTokenVerifier verifier;
    @MockitoSpyBean EventMediaStorage storage;
    long owner;
    long guardian;
    UUID session;

    @BeforeEach
    void fixtures() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        String suffix = UUID.randomUUID().toString();
        owner = user("owner-" + suffix);
        guardian = user("guardian-" + suffix);
        user("admin-" + suffix);
        session = UUID.randomUUID();
        jdbc.update("INSERT INTO safe_return_sessions (id,user_id,status,started_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP)",
                session, owner);
        // Only the common verifier boundary is faked; filters, HTTP, storage and PostgreSQL are real.
        doAnswer(invocation -> {
            String token = invocation.getArgument(0);
            if (!Set.of("owner", "guardian", "admin").contains(token)) throw new IllegalArgumentException("invalid token");
            return new VerifiedIdentity(token + "-" + suffix, "admin".equals(token));
        }).when(verifier).verify(anyString());
    }

    @Test
    void locationAndEventWritesCommitThroughHttp() throws Exception {
        long registration = jdbc.queryForObject("""
                INSERT INTO guardian_registrations (user_id,guardian_id,status,can_view_location)
                VALUES (?,?,'ACCEPTED',1) RETURNING id
                """, Long.class, owner, guardian);
        expect(request("POST", "/api/locations", "owner", """
                {"sessionId":"%s","latitude":37.5,"longitude":127.0,"recordedAt":"2026-11-08T10:00:00+09:00"}
                """.formatted(session)), 201);
        var latest = request("GET", "/api/sessions/" + session + "/locations/latest", "guardian", null);
        expect(latest, 200);
        assertThat(latest.body()).contains("\"recordedAt\":\"2026-11-08T01:00:00Z\"");
        long event = createEvent();
        expect(request("PATCH", "/api/risk-events/" + event + "/status", "owner", "{\"status\":\"RESOLVED\"}"), 200);
        assertThat(jdbc.queryForObject("SELECT status FROM risk_events WHERE id=?", String.class, event)).isEqualTo("RESOLVED");
        jdbc.update("UPDATE guardian_registrations SET status='REVOKED' WHERE id=?", registration);
        expect(request("GET", "/api/sessions/" + session + "/locations/latest", "guardian", null), 403);
    }

    @Test
    void mediaUploadListAndDeleteCommitBothFileAndMetadata() throws Exception {
        long event = createEvent();
        MediaRow media = upload(event);
        assertThat(Files.readAllBytes(MEDIA_ROOT.resolve(media.key()))).isEqualTo(MEDIA_CONTENTS);
        var response = request("GET", mediaPath(event), "admin", null);
        expect(response, 200);
        assertThat(response.body()).contains("\"storageUrl\":\"" + media.key() + "\"");
        expect(request("DELETE", mediaPath(event) + "/" + media.id(), "admin", null), 204);
        assertThat(mediaCount(media.id())).isZero();
        assertThat(MEDIA_ROOT.resolve(media.key())).doesNotExist();
    }

    @Test
    void failedFileDeletionPreservesMetadataAndCanBeRetried() throws Exception {
        long event = createEvent();
        MediaRow media = upload(event);
        doThrow(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_STORAGE_ERROR", "Injected storage failure"))
                .when(storage).deleteWithRollback(media.key());
        try {
            var failure = request("DELETE", mediaPath(event) + "/" + media.id(), "admin", null);
            expect(failure, 500);
            assertThat(failure.body()).contains("MEDIA_STORAGE_ERROR");
            assertThat(mediaCount(media.id())).isEqualTo(1);
            assertThat(Files.readAllBytes(MEDIA_ROOT.resolve(media.key()))).isEqualTo(MEDIA_CONTENTS);
        } finally {
            doCallRealMethod().when(storage).deleteWithRollback(media.key());
        }
        expect(request("DELETE", mediaPath(event) + "/" + media.id(), "admin", null), 204);
        assertThat(mediaCount(media.id())).isZero();
        assertThat(MEDIA_ROOT.resolve(media.key())).doesNotExist();
    }

    @Test
    void databaseDeleteFailureRestoresFileAndReturnsServerError() throws Exception {
        long event = createEvent();
        MediaRow media = upload(event);
        installRejectingTrigger("DELETE");
        try {
            var failure = request("DELETE", mediaPath(event) + "/" + media.id(), "admin", null);
            // An internal error dispatch must not turn the database failure into a misleading 401/403.
            expect(failure, 500);
            assertThat(mediaCount(media.id())).isEqualTo(1);
            assertThat(Files.readAllBytes(MEDIA_ROOT.resolve(media.key()))).isEqualTo(MEDIA_CONTENTS);
        } finally {
            removeRejectingTrigger();
        }
        expect(request("DELETE", mediaPath(event) + "/" + media.id(), "admin", null), 204);
        assertThat(mediaCount(media.id())).isZero();
        assertThat(MEDIA_ROOT.resolve(media.key())).doesNotExist();
    }

    @Test
    void databaseInsertFailureRollsBackUploadedFile() throws Exception {
        long event = createEvent();
        List<Path> before = mediaFiles();
        installRejectingTrigger("INSERT");
        try {
            expect(multipart(event, MEDIA_CONTENTS), 500);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_event_medias WHERE event_id=?", Integer.class, event)).isZero();
            assertThat(mediaFiles()).containsExactlyInAnyOrderElementsOf(before);
        } finally {
            removeRejectingTrigger();
        }
    }

    @Test
    void realForeignKeysRejectInvalidRows() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO risk_events (session_id,event_type,description) VALUES (?,'SOS','test')", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO risk_event_medias (event_id,media_type) VALUES (-1,'AUDIO')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO location_data (session_id,latitude,longitude,recorded_at) VALUES (?,37.5,127,CURRENT_TIMESTAMP)", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void servletRejectsOversizedUploadsWithoutSavingAnything() throws Exception {
        long event = createEvent();
        List<Path> before = mediaFiles();
        expect(multipart(event, new byte[10 * 1024 * 1024 + 1]), 413);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_event_medias WHERE event_id=?", Integer.class, event)).isZero();
        assertThat(mediaFiles()).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void realSecurityChainRejectsUnauthenticatedAndNonAdminRequests() throws Exception {
        expect(request("GET", mediaPath(1), null, null), 401);
        expect(request("GET", mediaPath(1), "invalid", null), 401);
        expect(request("GET", mediaPath(1), "owner", null), 403);
        // Direct access remains blocked, even when internal ERROR dispatches are permitted.
        assertThat(request("GET", "/error", "admin", null).statusCode()).isIn(401, 403);
    }

    private long createEvent() throws Exception {
        expect(request("POST", "/api/risk-events", "owner", """
                {"sessionId":"%s","eventType":"SOS","description":"runtime test","detectedAt":"2026-11-08T01:00:00Z"}
                """.formatted(session)), 201);
        return jdbc.queryForObject("SELECT id FROM risk_events WHERE session_id=?", Long.class, session);
    }

    private MediaRow upload(long event) throws Exception {
        expect(multipart(event, MEDIA_CONTENTS), 201);
        return jdbc.queryForObject("SELECT id,storage_url FROM risk_event_medias WHERE event_id=?",
                (rs, row) -> new MediaRow(rs.getLong("id"), rs.getString("storage_url")), event);
    }

    private HttpResponse<String> request(String method, String path, String token, String json) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (json != null) builder.header("Content-Type", "application/json");
        return HTTP.send(builder.method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> multipart(long event, byte[] bytes) throws Exception {
        String boundary = "safepath-" + UUID.randomUUID();
        byte[] start = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"mediaType\"\r\n\r\nAUDIO\r\n--"
                + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] end = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + mediaPath(event)))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer admin")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(start),
                        HttpRequest.BodyPublishers.ofByteArray(bytes), HttpRequest.BodyPublishers.ofByteArray(end))).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void installRejectingTrigger(String operation) {
        if (!Set.of("INSERT", "DELETE").contains(operation)) throw new IllegalArgumentException(operation);
        jdbc.execute("""
                CREATE FUNCTION reject_media_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced audit failure' USING ERRCODE='23514'; END; $$
                """);
        jdbc.execute("CREATE TRIGGER reject_media_mutation BEFORE " + operation
                + " ON risk_event_medias FOR EACH ROW EXECUTE FUNCTION reject_media_mutation()");
    }

    private void removeRejectingTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_media_mutation ON risk_event_medias");
        jdbc.execute("DROP FUNCTION IF EXISTS reject_media_mutation()");
    }

    private long user(String uid) {
        return jdbc.queryForObject("INSERT INTO users (firebase_uid,name,phone_hash,phone_encrypted,email) VALUES (?,'runtime',?,'test',?) RETURNING id",
                Long.class, uid, uid, uid + "@example.test");
    }

    private int mediaCount(long id) { return jdbc.queryForObject("SELECT count(*) FROM risk_event_medias WHERE id=?", Integer.class, id); }
    private String mediaPath(long event) { return "/api/risk-events/" + event + "/medias"; }
    private List<Path> mediaFiles() throws IOException { try (var files = Files.list(MEDIA_ROOT)) { return files.toList(); } }
    private static void expect(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(status);
    }
    private record MediaRow(long id, String key) {}

    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("safepath-runtime-"); }
        catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    @AfterAll
    static void cleanup(@Autowired JdbcTemplate jdbc) throws IOException {
        HTTP.close();
        if (!SCHEMA.matches("api_audit_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe test schema");
        try {
            jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        } finally {
            try (var paths = Files.walk(MEDIA_ROOT)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
