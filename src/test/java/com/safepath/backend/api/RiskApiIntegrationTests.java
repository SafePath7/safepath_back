package com.safepath.backend.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;

import com.safepath.backend.support.IsolatedTestDatabase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import com.safepath.backend.security.FirebaseIdTokenVerifier;
import com.safepath.backend.security.FirebaseIdTokenVerifier.VerifiedIdentity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RiskApiIntegrationTests {
    private static final IsolatedTestDatabase DATABASE = new IsolatedTestDatabase();
    private static final Path MEDIA_ROOT = temporaryDirectory();

    @DynamicPropertySource
    static void configureStorage(DynamicPropertyRegistry registry) {
        DATABASE.configure(registry);
        registry.add("safepath.media.root", MEDIA_ROOT::toString);
    }

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FirebaseIdTokenVerifier verifier;

    long owner;
    long guardian;
    long other;
    long admin;
    UUID session;

    @BeforeEach
    void setup() {
        DATABASE.assertIsolated(jdbc);
        owner = user("owner");
        guardian = user("guardian");
        other = user("other");
        admin = user("admin");
        session = UUID.randomUUID();
        jdbc.update("INSERT INTO safe_return_sessions (id,user_id,status,started_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP)", session, owner);
        when(verifier.verify(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            if (!java.util.Set.of("owner", "guardian", "other", "admin").contains(token)) {
                throw new IllegalArgumentException("invalid token");
            }
            return new VerifiedIdentity(token, "admin".equals(token));
        });
    }

    @Test
    void locationWritesRequireOwnerAndLatestUsesRecordedTimeThenId() throws Exception {
        mvc.perform(post("/api/locations").header("Authorization", "Bearer guardian").contentType("application/json")
                .content(location(1, "2026-11-08T00:00:00Z"))).andExpect(status().isForbidden());
        for (String body : new String[] {location(10, "2026-11-08T00:02:00Z"),
                location(20, "2026-11-08T00:01:00Z"), location(30, "2026-11-08T00:02:00Z")}) {
            mvc.perform(post("/api/locations").header("Authorization", "Bearer owner").contentType("application/json")
                    .content(body)).andExpect(status().isCreated());
        }
        mvc.perform(get(latest()).header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("latitude").value(30.0))
                .andExpect(jsonPath("recordedAt").value("2026-11-08T00:02:00Z"));
    }

    @Test
    void latestLocationEnforcesAcceptedDirectionalPermission() throws Exception {
        mvc.perform(post("/api/locations").header("Authorization", "Bearer owner").contentType("application/json")
                .content(location(37, "2026-11-08T00:00:00Z"))).andExpect(status().isCreated());
        long id = jdbc.queryForObject("INSERT INTO guardian_registrations (user_id,guardian_id) VALUES (?,?) RETURNING id", Long.class, owner, guardian);
        jdbc.update("UPDATE guardian_registrations SET can_view_location=1 WHERE id=?", id);
        mvc.perform(get(latest()).header("Authorization", "Bearer guardian")).andExpect(status().isForbidden());
        jdbc.update("UPDATE guardian_registrations SET status='ACCEPTED' WHERE id=?", id);
        mvc.perform(get(latest()).header("Authorization", "Bearer guardian")).andExpect(status().isOk());
        mvc.perform(get(latest()).header("Authorization", "Bearer admin")).andExpect(status().isForbidden());
        jdbc.update("UPDATE guardian_registrations SET status='REVOKED' WHERE id=?", id);
        mvc.perform(get(latest()).header("Authorization", "Bearer guardian")).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"91", "-91", "\"NaN\"", "\"Infinity\"", "null"})
    void invalidCoordinatesDoNotPersist(String latitude) throws Exception {
        mvc.perform(post("/api/locations").header("Authorization", "Bearer owner").contentType("application/json")
                .content("{\"sessionId\":\"" + session + "\",\"latitude\":" + latitude
                        + ",\"longitude\":127,\"recordedAt\":\"2026-11-08T00:00:00Z\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));
        assertThat(count("location_data")).isZero();
    }

    @Test
    void missingLocationAndSessionReturn404() throws Exception {
        mvc.perform(get(latest()).header("Authorization", "Bearer owner")).andExpect(status().isNotFound());
        mvc.perform(get("/api/sessions/" + UUID.randomUUID() + "/locations/latest")
                .header("Authorization", "Bearer owner")).andExpect(status().isNotFound());
    }

    @Test
    void riskEventIsOwnerCreatedAndOwnerOrAdminCanUpdateStatus() throws Exception {
        mvc.perform(post("/api/risk-events").header("Authorization", "Bearer other").contentType("application/json")
                .content(event())).andExpect(status().isForbidden());
        long id = createEvent();
        mvc.perform(patch("/api/risk-events/" + id + "/status").header("Authorization", "Bearer guardian")
                .contentType("application/json").content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE risk_events SET updated_at=TIMESTAMP '2000-01-01' WHERE id=?", id);
        mvc.perform(patch("/api/risk-events/" + id + "/status").header("Authorization", "Bearer admin")
                .contentType("application/json").content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("CONFIRMED"));
        mvc.perform(patch("/api/risk-events/" + id + "/status").header("Authorization", "Bearer owner")
                .contentType("application/json").content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("RESOLVED"));
        assertThat(jdbc.queryForObject("SELECT updated_at > TIMESTAMP '2000-01-01' FROM risk_events WHERE id=?", Boolean.class, id)).isTrue();
    }

    @Test
    void eventStatusAndDescriptionValidationAreEnforced() throws Exception {
        mvc.perform(post("/api/risk-events").header("Authorization", "Bearer owner").contentType("application/json")
                .content(event().replace("도움 요청", " "))).andExpect(status().isBadRequest());
        long id = createEvent();
        for (String value : new String[] {"", " ", "A".repeat(21)}) {
            mvc.perform(patch("/api/risk-events/" + id + "/status").header("Authorization", "Bearer owner")
                    .contentType("application/json").content("{\"status\":\"" + value + "\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void adminCanUploadListAndDeleteRealMediaWithMetadata() throws Exception {
        long eventId = createEvent();
        var file = new MockMultipartFile("file", "../voice.wav", "audio/wav", new byte[] {1, 2, 3});
        mvc.perform(multipart("/api/risk-events/" + eventId + "/medias").file(file).param("mediaType", "AUDIO")
                .header("Authorization", "Bearer admin"))
                .andExpect(status().isCreated()).andExpect(jsonPath("fileName").value("voice.wav"));
        long mediaId = jdbc.queryForObject("SELECT id FROM risk_event_medias", Long.class);
        String key = jdbc.queryForObject("SELECT storage_url FROM risk_event_medias", String.class);
        assertThat(Files.exists(MEDIA_ROOT.resolve(key))).isTrue();
        mvc.perform(get("/api/risk-events/" + eventId + "/medias").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(mediaId));
        mvc.perform(delete("/api/risk-events/" + eventId + "/medias/" + mediaId).header("Authorization", "Bearer admin"))
                .andExpect(status().isNoContent());
        assertThat(count("risk_event_medias")).isZero();
        assertThat(Files.exists(MEDIA_ROOT.resolve(key))).isFalse();
    }

    @Test
    void mediaEndpointsRequireAdminAndScopeMediaToItsEvent() throws Exception {
        long eventId = createEvent();
        var file = new MockMultipartFile("file", "photo.png", "image/png", new byte[] {1});
        mvc.perform(multipart("/api/risk-events/" + eventId + "/medias").file(file).param("mediaType", "IMAGE")
                .header("Authorization", "Bearer owner")).andExpect(status().isForbidden());
        mvc.perform(get("/api/risk-events/" + eventId + "/medias").header("Authorization", "Bearer guardian"))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/risk-events/" + eventId + "/medias").file(file).param("mediaType", "IMAGE")
                .header("Authorization", "Bearer admin")).andExpect(status().isCreated());
        long mediaId = jdbc.queryForObject("SELECT id FROM risk_event_medias", Long.class);
        long anotherEvent = createEvent();
        mvc.perform(delete("/api/risk-events/" + anotherEvent + "/medias/" + mediaId).header("Authorization", "Bearer admin"))
                .andExpect(status().isNotFound());
        assertThat(count("risk_event_medias")).isEqualTo(1);
        mvc.perform(delete("/api/risk-events/" + eventId + "/medias/" + mediaId).header("Authorization", "Bearer owner"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsEmptyOversizedAndUnclassifiedMedia() throws Exception {
        long id = createEvent();
        String url = "/api/risk-events/" + id + "/medias";
        mvc.perform(multipart(url).file(new MockMultipartFile("file", new byte[0])).param("mediaType", "AUDIO")
                .header("Authorization", "Bearer admin")).andExpect(status().isBadRequest());
        mvc.perform(multipart(url).file(new MockMultipartFile("file", new byte[10 * 1024 * 1024 + 1]))
                .param("mediaType", "AUDIO").header("Authorization", "Bearer admin"))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart(url).file(new MockMultipartFile("file", new byte[] {1})).param("mediaType", "")
                .header("Authorization", "Bearer admin")).andExpect(status().isBadRequest());
        assertThat(count("risk_event_medias")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"risk_events", "risk_event_medias"})
    void databaseColumnDefinitionsMatchProvidedTables(String table) {
        var actual = jdbc.query("""
                SELECT column_name, data_type, character_maximum_length, is_nullable, is_identity
                FROM information_schema.columns WHERE table_schema=current_schema() AND table_name=?
                ORDER BY ordinal_position
                """, (rs, row) -> {
                    String length = rs.getObject("character_maximum_length") == null ? ""
                            : "(" + rs.getInt("character_maximum_length") + ")";
                    return rs.getString("column_name") + ":" + rs.getString("data_type") + length
                            + ("YES".equals(rs.getString("is_nullable")) ? ":NULL" : ":NOT NULL")
                            + ("YES".equals(rs.getString("is_identity")) ? ":IDENTITY" : "");
                }, table);
        List<String> expected = switch (table) {
            case "risk_events" -> List.of(
                    "id:bigint:NOT NULL:IDENTITY", "session_id:uuid:NOT NULL",
                    "event_type:character varying(20):NOT NULL", "description:character varying(500):NOT NULL",
                    "status:character varying(20):NOT NULL", "detected_at:timestamp without time zone:NOT NULL",
                    "created_at:timestamp without time zone:NOT NULL", "updated_at:timestamp without time zone:NOT NULL");
            case "risk_event_medias" -> List.of(
                    "id:bigint:NOT NULL:IDENTITY", "event_id:bigint:NOT NULL",
                    "media_type:character varying(20):NOT NULL", "file_name:character varying(255):NULL",
                    "storage_url:character varying(2048):NULL", "uploaded_at:timestamp without time zone:NULL",
                    "created_at:timestamp without time zone:NOT NULL", "updated_at:timestamp without time zone:NOT NULL");
            default -> throw new IllegalArgumentException(table);
        };
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void databaseGeneratesIdsAndTheSpecifiedDefaultValues() {
        var risk = jdbc.queryForMap("""
                INSERT INTO risk_events (session_id,event_type,description) VALUES (?,'SOS','test') RETURNING *
                """, session);
        assertThat((Long) risk.get("id")).isPositive();
        assertThat(risk.get("session_id")).isEqualTo(session);
        assertThat(risk.get("status")).isEqualTo("PENDING");
        for (String field : List.of("detected_at", "created_at", "updated_at")) {
            assertThat(risk.get(field)).isNotNull();
        }
    }

    @Test
    void foreignKeysReferenceExistingUsersSessionsAndEvents() {
        var keys = jdbc.query("""
                SELECT source.table_name, source.column_name, target.table_name AS target_table,
                       target.column_name AS target_column
                FROM information_schema.table_constraints constraints
                JOIN information_schema.key_column_usage source
                  ON source.constraint_schema=constraints.constraint_schema
                 AND source.constraint_name=constraints.constraint_name
                JOIN information_schema.constraint_column_usage target
                  ON target.constraint_schema=constraints.constraint_schema
                 AND target.constraint_name=constraints.constraint_name
                WHERE constraints.constraint_type='FOREIGN KEY' AND constraints.table_schema=current_schema()
                  AND constraints.table_name IN ('risk_events','risk_event_medias')
                """, (rs, row) -> rs.getString("table_name") + "." + rs.getString("column_name")
                        + "->" + rs.getString("target_table") + "." + rs.getString("target_column"));
        assertThat(keys).containsExactlyInAnyOrder(
                "risk_events.session_id->safe_return_sessions.id",
                "risk_event_medias.event_id->risk_events.id");
    }

    @Test
    void nullableMediaMetadataCanBeListedAndDeleted() throws Exception {
        long eventId = createEvent();
        var media = jdbc.queryForMap("""
                INSERT INTO risk_event_medias (event_id,media_type) VALUES (?,'AUDIO') RETURNING *
                """, eventId);
        long mediaId = ((Number) media.get("id")).longValue();
        assertThat(mediaId).isPositive();
        assertThat(media.get("event_id")).isEqualTo(eventId);
        for (String field : List.of("file_name", "storage_url", "uploaded_at")) {
            assertThat(media.get(field)).isNull();
        }
        assertThat(media.get("created_at")).isNotNull();
        assertThat(media.get("updated_at")).isNotNull();
        mvc.perform(get("/api/risk-events/" + eventId + "/medias").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(mediaId))
                .andExpect(jsonPath("$[0].fileName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].storageUrl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].uploadedAt").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(delete("/api/risk-events/" + eventId + "/medias/" + mediaId)
                .header("Authorization", "Bearer admin")).andExpect(status().isNoContent());
        assertThat(count("risk_event_medias")).isZero();
    }

    @Test
    void featureExposesExactlyItsSevenSpecifiedEndpoints() {
        var endpoints = mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().getPackageName().equals("com.safepath.backend.risk"))
                .flatMap(entry -> entry.getKey().getPatternValues().stream().flatMap(path ->
                        entry.getKey().getMethodsCondition().getMethods().stream().map(method -> method + " " + path)))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(endpoints).containsExactlyInAnyOrder(
                "POST /api/locations",
                "GET /api/sessions/{sessionId}/locations/latest",
                "POST /api/risk-events",
                "PATCH /api/risk-events/{eventId}/status",
                "POST /api/risk-events/{eventId}/medias",
                "GET /api/risk-events/{eventId}/medias",
                "DELETE /api/risk-events/{eventId}/medias/{mediaId}");
    }

    private long createEvent() throws Exception {
        mvc.perform(post("/api/risk-events").header("Authorization", "Bearer owner").contentType("application/json")
                .content(event())).andExpect(status().isCreated()).andExpect(jsonPath("status").value("PENDING"));
        return jdbc.queryForObject("SELECT MAX(id) FROM risk_events", Long.class);
    }

    private String event() {
        return "{\"sessionId\":\"" + session + "\",\"eventType\":\"FALL\",\"description\":\"도움 요청\",\"detectedAt\":\"2026-11-08T00:00:00Z\",\"status\":\"RESOLVED\"}";
    }

    private String location(double latitude, String time) {
        return "{\"sessionId\":\"" + session + "\",\"latitude\":" + latitude + ",\"longitude\":127,\"recordedAt\":\"" + time + "\"}";
    }

    private String latest() { return "/api/sessions/" + session + "/locations/latest"; }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private long user(String uid) {
        return jdbc.queryForObject("""
                INSERT INTO users (firebase_uid,name,phone_hash,phone_encrypted,email)
                VALUES (?,'테스트 사용자',?,'test',?) RETURNING id
                """, Long.class, uid, uid, uid + "@example.test");
    }

    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("safepath-api-media-"); }
        catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    @AfterTransaction
    void rolledBackUploadsLeaveNoFiles() throws IOException {
        try (var paths = Files.list(MEDIA_ROOT)) { assertThat(paths.toList()).isEmpty(); }
    }

    @AfterAll
    static void cleanup(@Autowired JdbcTemplate jdbc) throws IOException {
        try {
            DATABASE.cleanup(jdbc);
        } finally {
            try (var paths = Files.walk(MEDIA_ROOT)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
