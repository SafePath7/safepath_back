package com.safepath.backend.risk;

import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.common.auth.CurrentUser.Actor;
import com.safepath.backend.common.auth.FirebaseAdminRoleLookup;
import com.safepath.backend.config.FirebaseConfig;
import com.safepath.backend.notification.NotificationService;
import com.safepath.backend.risk.dto.EventRequest;
import com.safepath.backend.risk.service.EventMediaService;
import com.safepath.backend.risk.service.RiskService;
import com.safepath.backend.risk.service.RiskSessionBridge;
import com.safepath.backend.common.util.UtcTime;
import com.safepath.backend.session.service.SafeReturnSessionService;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import com.safepath.backend.session.store.SessionChannelStore;
import com.safepath.backend.websocket.InMemoryChannelRegistry;
import com.safepath.backend.websocket.LocationUpdateController;
import com.safepath.backend.websocket.dto.LocationUpdateRequest;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"safepath.media.provider=local", "server.address=127.0.0.1",
                "spring.profiles.active=risk-persistence-test", "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SAFEPATH_RISK_TEST_DB_URL",
        matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/safepath_risk_eventkey_test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RiskEventPersistenceTests {
    private static final String EVENTS = "/api/risk-events";
    private static final String SESSIONS = "/api/safe-return/sessions";
    private static final String MONITORING = "/api/guardian/monitoring/sessions";
    @TempDir static Path mediaRoot;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired SafeReturnSessionService sessions;
    @Autowired ActiveSessionStore activeSessions;
    @Autowired SelectedGuardianStore guardians;
    @Autowired SessionChannelStore sessionChannels;
    @Autowired InMemoryChannelRegistry channels;
    @Autowired LocationUpdateController locations;
    @Autowired RiskService risks;
    @MockitoSpyBean RiskSessionBridge riskSessions;
    @Autowired EventMediaService media;
    @MockitoBean FirebaseConfig firebase;
    @MockitoBean FirebaseTokenService tokens;
    @MockitoBean FirebaseAdminRoleLookup roles;
    @MockitoBean NotificationService notifications;

    private UUID sessionId;
    private Long owner;
    private Long guardian;

    @DynamicPropertySource
    static void isolatedResources(DynamicPropertyRegistry properties) {
        String url = requiredEnvironment("SAFEPATH_RISK_TEST_DB_URL");
        String username = requiredEnvironment("SAFEPATH_RISK_TEST_DB_USERNAME");
        String password = requiredEnvironment("SAFEPATH_RISK_TEST_DB_PASSWORD");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.hikari.jdbc-url", () -> url);
        properties.add("spring.datasource.username", () -> username);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.flyway.url", () -> url);
        properties.add("spring.flyway.user", () -> username);
        properties.add("spring.flyway.password", () -> password);
        properties.add("safepath.media.root", () -> mediaRoot.toString());
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing test environment variable: " + name);
        return value;
    }

    @BeforeEach
    void startOwnedSession() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("safepath_risk_eventkey_test");
        owner = member();
        guardian = member();
        jdbc.update("""
                INSERT INTO guardian_registrations (user_id,guardian_id,status,can_view_location)
                VALUES (?,?,'ACCEPTED',1)
                """, owner, guardian);
        when(tokens.verifyIdToken("owner-token")).thenReturn(uid(owner));
        when(tokens.verifyIdToken("guardian-token")).thenReturn(uid(guardian));
        try (var client = HttpClient.newHttpClient()) {
            var response = requestHttp(client, "POST", SESSIONS, "owner-token",
                    "{\"guardianIds\":[" + guardian + "]}");
            assertThat(response.statusCode()).isEqualTo(201);
            sessionId = UUID.fromString(new JsonMapper().readTree(response.body()).path("id").asString());
        }
    }

    @AfterEach
    void removeOnlyTestFixtures() {
        if (sessionId != null) {
            if (activeSessions.findById(sessionId).isPresent()) {
                sessions.endSession(sessionId, owner);
            }
            jdbc.update("DELETE FROM risk_event_medias WHERE event_id IN (SELECT id FROM risk_events WHERE session_id=?)", sessionId);
            jdbc.update("DELETE FROM risk_events WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM safe_return_sessions WHERE id=?", sessionId);
        }
        if (guardian != null) {
            jdbc.update("DELETE FROM guardian_registrations WHERE user_id=? AND guardian_id=?", owner, guardian);
            jdbc.update("DELETE FROM users WHERE id=? AND firebase_uid=?", guardian, uid(guardian));
        }
        if (owner != null) {
            jdbc.update("DELETE FROM users WHERE id=? AND firebase_uid=?", owner, uid(owner));
        }
    }

    @Test
    void ordinarySessionUsesMemoryAndCleansUpChannelsWithoutWritingASnapshot() {
        assertThat(activeSessions.findById(sessionId)).isPresent();
        String channelId = sessionChannels.findBySessionId(sessionId).orElseThrow();
        assertThat(channels.exists(channelId)).isTrue();
        assertThat(snapshotCount()).isZero();
        sessions.endSession(sessionId, owner);
        assertThat(activeSessions.findById(sessionId)).isEmpty();
        assertThat(sessionChannels.findBySessionId(sessionId)).isEmpty();
        assertThat(guardians.findBySessionId(sessionId)).isEmpty();
        assertThat(channels.exists(channelId)).isFalse();
        assertThat(snapshotCount()).isZero();
    }

    @Test
    void firstEventSavesOwnedSnapshotAndDistinctEventsReuseIt() throws Exception {
        var created = mvc.perform(createRequest(sessionId, "owner-token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        var id = new JsonMapper()
                .readTree(created.getResponse().getContentAsString()).path("id").asLong();
        assertThat(id).isPositive();
        assertThat(jdbc.queryForObject("SELECT status FROM risk_events WHERE id=? AND session_id=?",
                String.class, id, sessionId)).isEqualTo("PENDING");
        var second = risks.createEvent(new Actor(owner, false), event("second event"));
        assertThat(second.status()).isEqualTo("PENDING");
        assertThat(snapshotCount()).isOne();
        assertThat(jdbc.queryForList("""
                SELECT s.user_id FROM risk_events e JOIN safe_return_sessions s ON e.session_id=s.id
                WHERE s.id=? ORDER BY e.id
                """, Long.class, sessionId)).containsExactly(owner, owner);
        verify(notifications, never()).notifyRiskEventUpdate(anyLong(), anyString());
    }

    @Test
    void databaseRejectsInvalidEventAndRollsBackTheNewSnapshot() {
        assertThatThrownBy(() -> risks.createEvent(new Actor(owner, false), event("x".repeat(501))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(snapshotCount()).isZero();
        assertThat(activeSessions.findById(sessionId)).isPresent();
        verify(notifications, never()).notifyRiskEventUpdate(anyLong(), anyString());
    }

    @Test
    void rejectsUnknownOrUnownedSessionsAndMalformedRequests() throws Exception {
        mvc.perform(createRequest(UUID.randomUUID(), "owner-token")).andExpect(status().isNotFound());
        mvc.perform(createRequest(sessionId, "guardian-token")).andExpect(status().isForbidden());
        mvc.perform(post(EVENTS).header("Authorization", "Bearer owner-token")
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        assertThat(snapshotCount()).isZero();
    }

    @Test
    void statusChangeRequiresOwnerOrAdminAndValidIdAndBody() throws Exception {
        long id = risks.createEvent(new Actor(owner, false), event("update target")).id();
        mvc.perform(statusRequest(id, "guardian-token", "{\"status\":\"CONFIRMED\"}")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT status FROM risk_events WHERE id=?", String.class, id)).isEqualTo("PENDING");
        mvc.perform(statusRequest(id, "owner-token", "{}")).andExpect(status().isBadRequest());
        mvc.perform(statusRequest(0, "owner-token", "{\"status\":\"REVIEWED\"}")).andExpect(status().isBadRequest());
        mvc.perform(statusRequest(id, "owner-token", "{\"status\":\"REVIEWED\"}")).andExpect(status().isOk());
        when(roles.isAdmin(uid(guardian))).thenReturn(true);
        mvc.perform(statusRequest(id, "guardian-token", "{\"status\":\"REVIEWED_AGAIN\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM risk_events WHERE id=?", String.class, id)).isEqualTo("REVIEWED_AGAIN");
        verify(notifications, never()).notifyRiskEventUpdate(anyLong(), anyString());

        mvc.perform(statusRequest(id, "guardian-token", "{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        verify(notifications).notifyRiskEventUpdate(owner, "danger_phrase");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "DISMISSED"})
    void confirmingEventNotifiesOnceAcrossRepeatedRequests(String previousStatus) throws Exception {
        long id = risks.createEvent(new Actor(owner, false), event("risk to confirm")).id();
        if ("DISMISSED".equals(previousStatus)) {
            mvc.perform(statusRequest(id, "owner-token", "{\"status\":\"DISMISSED\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISMISSED"));
        }
        verify(notifications, never()).notifyRiskEventUpdate(anyLong(), anyString());
        String confirmation = "{\"status\":\"CONFIRMED\"}";

        mvc.perform(statusRequest(id, "owner-token", confirmation))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(statusRequest(id, "owner-token", confirmation))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(jdbc.queryForObject("SELECT status FROM risk_events WHERE id=?", String.class, id))
                .isEqualTo("CONFIRMED");
        verify(notifications).notifyRiskEventUpdate(owner, "danger_phrase");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "DISMISSED"})
    void concurrentConfirmationsNotifyOnce(String previousStatus) throws Exception {
        var actor = new Actor(owner, false);
        long id = risks.createEvent(actor, event("risk to confirm concurrently")).id();
        if ("DISMISSED".equals(previousStatus)) {
            assertThat(risks.updateEvent(actor, id, previousStatus).status()).isEqualTo("DISMISSED");
        }
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> confirm = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Confirmation did not start");
                return risks.updateEvent(actor, id, "CONFIRMED").status();
            };
            var first = executor.submit(confirm);
            var second = executor.submit(confirm);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("CONFIRMED");
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("CONFIRMED");
        }

        assertThat(snapshotCount()).isOne();
        verify(notifications).notifyRiskEventUpdate(owner, "danger_phrase");
    }

    @Test
    void dismissingPendingEventRetainsRecordsWithoutNotification() throws Exception {
        long id = risks.createEvent(new Actor(owner, false), event("risk to dismiss")).id();

        mvc.perform(statusRequest(id, "owner-token", "{\"status\":\"DISMISSED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISMISSED"));

        assertThat(snapshotCount()).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM risk_events WHERE id=? AND session_id=?",
                String.class, id, sessionId)).isEqualTo("DISMISSED");
        verify(notifications, never()).notifyRiskEventUpdate(anyLong(), anyString());
    }

    @Test
    void mediaReferencesExistingEventAndDatabaseRejectsOrphanMetadata() throws Exception {
        long eventId = risks.createEvent(new Actor(owner, false), event("media target")).id();
        long missingId = -eventId;
        var admin = new Actor(guardian, true);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO risk_event_medias (event_id,media_type) VALUES (?,?)",
                missingId, "AUDIO")).isInstanceOf(DataIntegrityViolationException.class);
        when(roles.isAdmin(uid(guardian))).thenReturn(true);
        mvc.perform(multipart(EVENTS + "/{eventId}/medias", Long.MAX_VALUE).file(audio()).param("mediaType", "AUDIO")
                .header("Authorization", "Bearer guardian-token")).andExpect(status().isNotFound());
        assertThat(fileCount()).isZero();
        var saved = media.uploadEventMedia(admin, eventId, audio(), "AUDIO");
        try {
            assertThat(jdbc.queryForObject("SELECT event_id FROM risk_event_medias WHERE id=?", Long.class, saved.id()))
                    .isEqualTo(eventId);
            assertThat(fileCount()).isOne();
        } finally {
            media.deleteEventMedia(admin, eventId, saved.id());
        }
    }

    @Test
    void mediaMetadataFailureRollsBackAndRemovesTheStoredFile() throws Exception {
        long eventId = risks.createEvent(new Actor(owner, false), event("failing media target")).id();
        assertThatThrownBy(() -> media.uploadEventMedia(new Actor(guardian, true), eventId, audio(), "x".repeat(21)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_event_medias WHERE event_id=?", Long.class, eventId)).isZero();
        assertThat(fileCount()).isZero();
    }

    @Test
    void keyedCreateReturns201Then200WhileDifferentKeysCreateSeparateEvents() throws Exception {
        UUID key = UUID.randomUUID();
        var request = keyedEvent(key);

        var created = mvc.perform(createKeyedRequest(request, "owner-token"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.id").isNumber()).andReturn();
        long id = new JsonMapper().readTree(created.getResponse().getContentAsString()).path("id").asLong();
        assertThat(id).isPositive();
        mvc.perform(createKeyedRequest(request, "owner-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(createKeyedRequest(keyedEvent(UUID.randomUUID()), "owner-token"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber());

        assertThat(jdbc.queryForObject("SELECT id FROM risk_events WHERE event_key=?", Long.class, key))
                .isEqualTo(id);
        assertThat(eventCount()).isEqualTo(2);
        assertThat(snapshotCount()).isOne();
        assertThat(fileCount()).isZero();
        verify(notifications, never()).notifyRiskEventUpdate(anyLong(), anyString());
    }

    @Test
    void concurrentSameKeyRequestsCreateOneEvent() throws Exception {
        var request = keyedEvent(UUID.randomUUID());
        var actor = new Actor(owner, false);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<RiskService.EventCreation> submit = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Creation did not start");
                return risks.createOrReuseEvent(actor, request);
            };
            var first = executor.submit(submit);
            var second = executor.submit(submit);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(firstResult.response().id()).isEqualTo(secondResult.response().id());
            assertThat(firstResult.created()).isNotEqualTo(secondResult.created());
        }
        assertThat(eventCount()).isOne();
        assertThat(snapshotCount()).isOne();
        verify(notifications, never()).notifyRiskEventUpdate(anyLong(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMED", "DISMISSED"})
    void retryPreservesCurrentStatusAndDoesNotNotifyAgain(String currentStatus) throws Exception {
        var request = keyedEvent(UUID.randomUUID());
        var actor = new Actor(owner, false);
        long id = risks.createEvent(actor, request).id();
        risks.updateEvent(actor, id, currentStatus);

        mvc.perform(createKeyedRequest(request, "owner-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value(currentStatus));

        assertThat(jdbc.queryForObject("SELECT status FROM risk_events WHERE id=?", String.class, id))
                .isEqualTo(currentStatus);
        assertThat(eventCount()).isOne();
        verify(notifications, "CONFIRMED".equals(currentStatus) ? org.mockito.Mockito.times(1) : never())
                .notifyRiskEventUpdate(owner, request.eventType());
    }

    @Test
    void reusedKeyRejectsDifferentOwnerSessionOrCoreRequestFields() throws Exception {
        var request = keyedEvent(UUID.randomUUID());
        var actor = new Actor(owner, false);
        risks.createEvent(actor, request);

        mvc.perform(createKeyedRequest(request, "guardian-token"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.id").doesNotExist()).andExpect(jsonPath("$.sessionId").doesNotExist());
        for (var conflict : List.of(
                new EventRequest(UUID.randomUUID(), request.eventType(), request.description(),
                        request.detectedAt(), request.eventKey()),
                new EventRequest(sessionId, "scream", request.description(), request.detectedAt(), request.eventKey()),
                new EventRequest(sessionId, request.eventType(), "changed", request.detectedAt(), request.eventKey()),
                new EventRequest(sessionId, request.eventType(), request.description(),
                        request.detectedAt().plusSeconds(1), request.eventKey()))) {
            mvc.perform(createKeyedRequest(conflict, "owner-token"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"))
                    .andExpect(jsonPath("$.id").doesNotExist());
        }

        assertThat(eventCount()).isOne();
        assertThat(snapshotCount()).isOne();
        verify(notifications, never()).notifyRiskEventUpdate(anyLong(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"   \"", "\"not-a-uuid\"", "\"AAAAAAAAAAAAAAAAAAAAAA==\"",
            "\"00000000000000000000000000000000\"", "123", "[]", "{}", "true", "null"})
    void invalidEventKeyIsRejectedBeforeCreatingAnyRows(String jsonValue) throws Exception {
        String body = """
                {"sessionId":"%s","eventType":"danger_phrase","description":"invalid risk key",
                 "detectedAt":"2026-11-01T10:00:00Z","eventKey":%s}
                """.formatted(sessionId, jsonValue);
        mvc.perform(post(EVENTS).header("Authorization", "Bearer owner-token")
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());

        assertThat(eventCount()).isZero();
        assertThat(snapshotCount()).isZero();
        verify(notifications, never()).notifyRiskEventUpdate(anyLong(), anyString());
    }

    @Test
    void legacyRequestsWithoutKeyStillCreateDistinctEvents() throws Exception {
        mvc.perform(createRequest(sessionId, "owner-token")).andExpect(status().isCreated());
        mvc.perform(createRequest(sessionId, "owner-token")).andExpect(status().isCreated());
        assertThat(eventCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_events WHERE session_id=? AND event_key IS NULL",
                Long.class, sessionId)).isEqualTo(2);
        assertThat(snapshotCount()).isOne();
    }

    @Test
    void sequentialHttpUploadsPreserveEverySuccessfulFileAndEventState() throws Exception {
        var request = keyedEvent(UUID.randomUUID());
        var actor = new Actor(owner, false);
        long eventId = risks.createEvent(actor, request).id();
        String path = EVENTS + "/" + eventId + "/medias";
        try (var client = HttpClient.newHttpClient()) {
            var ids = new java.util.HashSet<Long>();
            for (int index = 0; index < 23; index++) {
                boolean audio = index < 12;
                String type = audio ? "audio" : "sensor";
                String name = type + "-" + index + (audio ? ".wav" : ".json");
                String submittedName = index == 0 ? "../" + name : name;
                byte[] bytes = audio ? pcmWave(index == 11 ? 9 * 1024 * 1024 : (index == 0 ? 420 : 100) * 1024)
                        : ("{\"sample\":" + index + "}").getBytes(StandardCharsets.UTF_8);
                var response = uploadHttp(client, path, submittedName,
                        audio ? "audio/wav" : "application/json", type, bytes);
                assertThat(response.statusCode()).isEqualTo(201);
                var body = new JsonMapper().readTree(response.body());
                long mediaId = body.path("id").asLong();
                assertThat(mediaId).isPositive();
                assertThat(ids.add(mediaId)).isTrue();
                assertThat(body.path("eventId").asLong()).isEqualTo(eventId);
                assertThat(body.path("mediaType").asString()).isEqualTo(type);
                assertThat(body.path("fileName").asString()).isEqualTo(name);

                String key = jdbc.queryForObject("""
                        SELECT storage_url FROM risk_event_medias
                        WHERE id=? AND event_id=? AND media_type=? AND file_name=?
                        """, String.class, mediaId, eventId, type, name);
                assertThat(UUID.fromString(key).toString()).isEqualTo(key);
                assertThat(Files.readAllBytes(mediaRoot.resolve(key))).isEqualTo(bytes);

                if (index == 0) {
                    var oversized = uploadHttp(client, path, "oversized.wav", "audio/wav", "audio",
                            new byte[10 * 1024 * 1024 + 1]);
                    assertThat(oversized.statusCode()).isEqualTo(413);
                    assertThat(new JsonMapper().readTree(oversized.body()).path("code").asString())
                            .isEqualTo("FILE_TOO_LARGE");
                    assertThat(mediaCount(eventId)).isOne();
                    assertThat(fileCount()).isOne();
                }
            }
            assertThat(mediaCount(eventId)).isEqualTo(23);
            assertThat(fileCount()).isEqualTo(23);
            mvc.perform(createKeyedRequest(request, "owner-token"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(eventId))
                    .andExpect(jsonPath("$.status").value("PENDING"));
            assertThat(eventCount()).isOne();
            assertThat(mediaCount(eventId)).isEqualTo(23);
            assertThat(fileCount()).isEqualTo(23);
            verify(notifications, never()).notifyRiskEventUpdate(anyLong(), anyString());
        } finally {
            for (long mediaId : jdbc.queryForList("SELECT id FROM risk_event_medias WHERE event_id=?",
                    Long.class, eventId)) {
                media.deleteEventMedia(new Actor(owner, true), eventId, mediaId);
            }
        }
    }

    @Test
    void invalidOrUnauthorizedMediaRequestsLeaveNoRowsOrFiles() throws Exception {
        long id = risks.createEvent(new Actor(owner, false), event("media access")).id();
        String path = EVENTS + "/" + id + "/medias";

        mvc.perform(multipart(path).file(audio()).param("mediaType", "audio"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart(path).file(audio()).param("mediaType", "audio")
                        .header("Authorization", "Bearer guardian-token"))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(EVENTS + "/{eventId}/medias", Long.MAX_VALUE).file(audio())
                        .param("mediaType", "audio").header("Authorization", "Bearer owner-token"))
                .andExpect(status().isNotFound());
        mvc.perform(multipart(path).file(audio()).param("mediaType", " ")
                        .header("Authorization", "Bearer owner-token"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(path).file(new MockMultipartFile("file", "empty.wav", "audio/wav", new byte[0]))
                        .param("mediaType", "audio").header("Authorization", "Bearer owner-token"))
                .andExpect(status().isBadRequest());
        assertThat(mediaCount(id)).isZero();
        assertThat(fileCount()).isZero();
    }

    @Test
    void storageReadFailureRemovesTemporaryFileAndDoesNotWriteMetadata() throws Exception {
        var actor = new Actor(owner, false);
        long id = risks.createEvent(actor, event("media storage failure")).id();
        var file = spy(audio());
        doThrow(new IOException("fixture stream failure")).when(file).getInputStream();

        assertThatThrownBy(() -> media.uploadEventMedia(actor, id, file, "audio"))
                .isInstanceOfSatisfying(com.safepath.backend.common.exception.ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("MEDIA_STORAGE_ERROR"));
        assertThat(mediaCount(id)).isZero();
        assertThat(fileCount()).isZero();
    }

    @Test
    void ordinarySessionRelaysLocationAndEndsWithoutPersistingRiskData() throws Exception {
        String channel = sessionChannels.findBySessionId(sessionId).orElseThrow();
        String active = MONITORING + "/users/" + owner + "/active";
        try (var client = HttpClient.newHttpClient()) {
            assertThat(requestHttp(client, "GET", SESSIONS + "/" + sessionId + "/status",
                    "owner-token", null).statusCode()).isEqualTo(200);
            var guardianStatus = requestHttp(client, "GET", active, "guardian-token", null);
            assertThat(guardianStatus.statusCode()).isEqualTo(200);
            assertThat(new JsonMapper().readTree(guardianStatus.body()).path("id").asString())
                    .isEqualTo(sessionId.toString());
            assertThat(requestHttp(client, "GET", active, "owner-token", null).statusCode()).isEqualTo(403);

            channels.join(channel, owner, "owner-location-session");
            channels.join(channel, guardian, "guardian-location-session");
            var location = new LocationUpdateRequest(37.5665, 126.9780, 8.5, Instant.now());
            var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            headers.setSessionId("owner-location-session");
            var published = locations.relay(channel, location, headers);
            assertThat(published.latitude()).isEqualTo(location.latitude());
            assertThat(published.senderId()).isEqualTo(owner.toString());
            headers.setSessionId("guardian-location-session");
            assertThatThrownBy(() -> locations.relay(channel, location, headers))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(snapshotCount()).isZero();
            assertThat(eventCount()).isZero();
            endSessionOverHttp(client);
            assertThat(requestHttp(client, "GET", active, "guardian-token", null).statusCode()).isEqualTo(204);
            assertThat(channels.exists(channel)).isFalse();
            assertThat(snapshotCount()).isZero();
            assertThat(eventCount()).isZero();
            verify(notifications, never()).notifyRiskEventUpdate(anyLong(), anyString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SAFE", "HELP", "TIMEOUT"})
    void riskSessionKeepsEvidenceAcrossAndroidDecisionAndSessionEnd(String decision) throws Exception {
        var mapper = new JsonMapper();
        var request = keyedEvent(UUID.randomUUID());
        String requestJson = mapper.writeValueAsString(request);
        try (var client = HttpClient.newHttpClient()) {
            var created = requestHttp(client, "POST", EVENTS, "owner-token", requestJson);
            assertThat(created.statusCode()).isEqualTo(201);
            long id = mapper.readTree(created.body()).path("id").asLong();
            assertThat(id).isPositive();
            assertThat(mapper.readTree(created.body()).path("status").asString()).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT user_id FROM safe_return_sessions WHERE id=?",
                    Long.class, sessionId)).isEqualTo(owner);
            var snapshot = jdbc.queryForMap("SELECT status, ended_at FROM safe_return_sessions WHERE id=?", sessionId);
            assertThat(snapshot).containsEntry("status", "ACTIVE").containsEntry("ended_at", null);

            byte[] wav = pcmWave(100 * 1024);
            byte[] sensor = "{\"motion\":true}".getBytes(StandardCharsets.UTF_8);
            String mediaPath = EVENTS + "/" + id + "/medias";
            try {
                assertThat(uploadHttp(client, mediaPath, "event.wav", "audio/wav", "audio", wav).statusCode())
                        .isEqualTo(201);
                assertThat(uploadHttp(client, mediaPath, "sensor.json", "application/json", "sensor", sensor).statusCode())
                        .isEqualTo(201);
                assertReplay(client, mapper, requestJson, id, "PENDING");
                assertEvidence(id, "PENDING", wav, sensor);
                verify(notifications, never()).notifyRiskEventUpdate(anyLong(), anyString());

                String statusPath = EVENTS + "/" + id + "/status";
                String confirmation = "{\"status\":\"CONFIRMED\"}";
                assertThat(requestHttp(client, "PATCH", statusPath, null, confirmation).statusCode()).isEqualTo(401);
                assertThat(requestHttp(client, "PATCH", statusPath, "guardian-token", confirmation).statusCode())
                        .isEqualTo(403);
                if ("SAFE".equals(decision)) {
                    assertThat(requestHttp(client, "PATCH", statusPath, "owner-token",
                            "{\"status\":\"DISMISSED\"}").statusCode()).isEqualTo(200);
                    assertEvidence(id, "DISMISSED", wav, sensor);
                    assertReplay(client, mapper, requestJson, id, "DISMISSED");
                    verify(notifications, never()).notifyRiskEventUpdate(anyLong(), anyString());
                }

                var committedStatus = new AtomicReference<String>();
                doAnswer(call -> {
                    committedStatus.set(jdbc.queryForObject("SELECT status FROM risk_events WHERE id=?",
                            String.class, id));
                    return null;
                }).when(notifications).notifyRiskEventUpdate(owner, request.eventType());
                // HELP and Android's timeout send the same confirmation PATCH; SAFE can later be confirmed.
                assertThat(requestHttp(client, "PATCH", statusPath, "owner-token", confirmation).statusCode())
                        .isEqualTo(200);
                assertThat(committedStatus.get()).isEqualTo("CONFIRMED");
                assertThat(requestHttp(client, "PATCH", statusPath, "owner-token", confirmation).statusCode())
                        .isEqualTo(200);
                assertReplay(client, mapper, requestJson, id, "CONFIRMED");
                assertEvidence(id, "CONFIRMED", wav, sensor);
                verify(notifications).notifyRiskEventUpdate(owner, request.eventType());

                endSessionOverHttp(client);
                assertEvidence(id, "CONFIRMED", wav, sensor);
                assertThat(snapshotStatus()).isEqualTo("ENDED");
                assertThat(snapshotEndedAt()).isNotNull();
            } finally {
                for (long mediaId : jdbc.queryForList("SELECT id FROM risk_event_medias WHERE event_id=?",
                        Long.class, id)) {
                    media.deleteEventMedia(new Actor(owner, true), id, mediaId);
                }
            }
        }
    }

    @Test
    void endedSnapshotKeepsFirstTimestampOnRetryAndLateEvent() throws Exception {
        var actor = new Actor(owner, false);
        long firstId = risks.createEvent(actor, keyedEvent(UUID.randomUUID())).id();
        var before = UtcTime.now();
        try (var client = HttpClient.newHttpClient()) {
            endSessionOverHttp(client);
            LocalDateTime endedAt = snapshotEndedAt();
            assertThat(endedAt).isBetween(before, UtcTime.now());
            assertThat(endedAt.getNano() % 1000).isZero();
            riskSessions.endSnapshot(sessionId, owner, UtcTime.toInstant(endedAt).plusSeconds(60));

            assertThat(requestHttp(client, "PATCH", SESSIONS + "/" + sessionId + "/end",
                    "owner-token", null).statusCode()).isEqualTo(404);
            var late = risks.createOrReuseEvent(actor, keyedEvent(UUID.randomUUID()));
            assertThat(late.created()).isTrue();
            assertThat(late.response().id()).isNotEqualTo(firstId);
            assertThat(snapshotStatus()).isEqualTo("ENDED");
            assertThat(snapshotEndedAt()).isEqualTo(endedAt);
            assertThat(snapshotCount()).isOne();
            assertThat(eventCount()).isEqualTo(2);
        }
    }

    @Test
    void endedOrdinarySessionRejectsLateFirstEventWithoutCreatingSnapshot() throws Exception {
        var request = keyedEvent(UUID.randomUUID());
        try (var client = HttpClient.newHttpClient()) {
            endSessionOverHttp(client);
            mvc.perform(createKeyedRequest(request, "owner-token")).andExpect(status().isNotFound());
        }
        assertThat(snapshotCount()).isZero();
        assertThat(eventCount()).isZero();
    }

    @Test
    void databaseFailureKeepsSessionAndChannelForRetry() throws Exception {
        risks.createEvent(new Actor(owner, false), keyedEvent(UUID.randomUUID()));
        String channel = sessionChannels.findBySessionId(sessionId).orElseThrow();
        doThrow(new DataAccessResourceFailureException("fixture DB outage")).doCallRealMethod()
                .when(riskSessions).endSnapshot(eq(sessionId), eq(owner), any(Instant.class));

        try (var client = HttpClient.newHttpClient()) {
            assertThat(requestHttp(client, "PATCH", SESSIONS + "/" + sessionId + "/end",
                    "owner-token", null).statusCode()).isEqualTo(500);
            assertThat(activeSessions.findById(sessionId)).isPresent();
            assertThat(sessionChannels.findBySessionId(sessionId)).contains(channel);
            assertThat(channels.exists(channel)).isTrue();
            assertThat(snapshotStatus()).isEqualTo("ACTIVE");
            assertThat(snapshotEndedAt()).isNull();

            endSessionOverHttp(client);
            assertThat(channels.exists(channel)).isFalse();
            assertThat(snapshotStatus()).isEqualTo("ENDED");
            assertThat(snapshotEndedAt()).isNotNull();
            assertThat(eventCount()).isOne();
        }
    }

    @Test
    void endingWaitsForFirstRiskEventTransactionToCommit() throws Exception {
        var actor = new Actor(owner, false);
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var endingStarted = new CountDownLatch(1);
        doAnswer(call -> {
            Object snapshot = call.callRealMethod();
            inserted.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Risk fixture timed out");
            return snapshot;
        }).when(AopTestUtils.<RiskSessionBridge>getUltimateTargetObject(riskSessions))
                .ensurePersistedOwner(actor, sessionId);

        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var creating = executor.submit(() -> risks.createOrReuseEvent(actor, keyedEvent(UUID.randomUUID())));
                assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(snapshotCount()).isZero();
                var ending = executor.submit(() -> {
                    endingStarted.countDown();
                    sessions.endSession(sessionId, owner);
                });
                assertThat(endingStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> ending.get(250, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(creating.get(10, TimeUnit.SECONDS).created()).isTrue();
                ending.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        assertThat(activeSessions.findById(sessionId)).isEmpty();
        assertThat(snapshotCount()).isOne();
        assertThat(snapshotStatus()).isEqualTo("ENDED");
        assertThat(snapshotEndedAt()).isNotNull();
        assertThat(eventCount()).isOne();
    }

    @Test
    void firstRiskEventWaitsForOrdinarySessionEndAndCannotCreateSnapshot() throws Exception {
        var actor = new Actor(owner, false);
        var request = keyedEvent(UUID.randomUUID());
        var endingEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var creationStarted = new CountDownLatch(1);
        doAnswer(call -> {
            endingEntered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("End fixture timed out");
            return call.callRealMethod();
        }).when(AopTestUtils.<RiskSessionBridge>getUltimateTargetObject(riskSessions))
                .endSnapshot(eq(sessionId), eq(owner.longValue()), any(Instant.class));

        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var ending = executor.submit(() -> sessions.endSession(sessionId, owner));
                assertThat(endingEntered.await(10, TimeUnit.SECONDS)).isTrue();
                var creating = executor.submit(() -> {
                    creationStarted.countDown();
                    return risks.createOrReuseEvent(actor, request);
                });
                assertThat(creationStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> creating.get(250, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();
                ending.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> creating.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(com.safepath.backend.common.exception.ApiException.class);
            } finally {
                release.countDown();
            }
        }
        assertThat(activeSessions.findById(sessionId)).isEmpty();
        assertThat(snapshotCount()).isZero();
        assertThat(eventCount()).isZero();
    }

    private String snapshotStatus() {
        return jdbc.queryForObject("SELECT status FROM safe_return_sessions WHERE id=?", String.class, sessionId);
    }

    private LocalDateTime snapshotEndedAt() {
        return jdbc.queryForObject("SELECT ended_at FROM safe_return_sessions WHERE id=?", LocalDateTime.class, sessionId);
    }

    private void assertReplay(HttpClient client, JsonMapper mapper, String requestJson, long id, String status)
            throws Exception {
        var replay = requestHttp(client, "POST", EVENTS, "owner-token", requestJson);
        assertThat(replay.statusCode()).isEqualTo(200);
        var body = mapper.readTree(replay.body());
        assertThat(body.path("id").asLong()).isEqualTo(id);
        assertThat(body.path("status").asString()).isEqualTo(status);
        assertThat(eventCount()).isOne();
        assertThat(snapshotCount()).isOne();
    }

    private void assertEvidence(long id, String status, byte[] wav, byte[] sensor) throws IOException {
        assertThat(jdbc.queryForObject("SELECT status FROM risk_events WHERE id=? AND session_id=?",
                String.class, id, sessionId)).isEqualTo(status);
        assertThat(snapshotCount()).isOne();
        assertThat(mediaCount(id)).isEqualTo(2);
        String audioKey = jdbc.queryForObject(
                "SELECT storage_url FROM risk_event_medias WHERE event_id=? AND media_type='audio'", String.class, id);
        String sensorKey = jdbc.queryForObject(
                "SELECT storage_url FROM risk_event_medias WHERE event_id=? AND media_type='sensor'", String.class, id);
        assertThat(Files.readAllBytes(mediaRoot.resolve(audioKey))).isEqualTo(wav);
        assertThat(Files.readAllBytes(mediaRoot.resolve(sensorKey))).isEqualTo(sensor);
    }

    private void endSessionOverHttp(HttpClient client) throws IOException, InterruptedException {
        String path = SESSIONS + "/" + sessionId;
        assertThat(requestHttp(client, "PATCH", path + "/end", "owner-token", null).statusCode()).isEqualTo(204);
        assertThat(activeSessions.findById(sessionId)).isEmpty();
        assertThat(requestHttp(client, "GET", path + "/status", "owner-token", null).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> requestHttp(HttpClient client, String method, String path, String token, String json)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (json != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> uploadHttp(HttpClient client, String path, String fileName, String contentType,
            String mediaType, byte[] bytes) throws IOException, InterruptedException {
        String boundary = "safepath-media-" + UUID.randomUUID();
        String start = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"mediaType\"\r\n\r\n"
                + mediaType + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + fileName + "\"\r\nContent-Type: " + contentType + "\r\n\r\n";
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer owner-token")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(start),
                        HttpRequest.BodyPublishers.ofByteArray(bytes),
                        HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n")))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private byte[] pcmWave(int length) {
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(length - 8)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(length - 44).array();
    }

    private long mediaCount(long eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM risk_event_medias WHERE event_id=?", Long.class, eventId);
    }

    private EventRequest keyedEvent(UUID key) {
        return new EventRequest(sessionId, "danger_phrase", "keyed risk",
                Instant.parse("2026-11-01T10:00:00.123456789Z"), key);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createKeyedRequest(
            EventRequest request, String token) {
        return post(EVENTS).header("Authorization", "Bearer " + token).contentType("application/json")
                .content(new JsonMapper().writeValueAsString(request));
    }

    private long eventCount() {
        return jdbc.queryForObject("SELECT count(*) FROM risk_events WHERE session_id=?", Long.class, sessionId);
    }

    private long member() {
        String marker = "risk-persistence-" + UUID.randomUUID();
        return jdbc.queryForObject("""
                WITH fixture AS (
                    SELECT nextval(pg_get_serial_sequence('users','id')) AS id
                )
                INSERT INTO users (id,firebase_uid,name,phone_hash,phone_encrypted,email)
                SELECT id,'risk-persistence-' || id,'Risk test',?,'synthetic',? FROM fixture RETURNING id
                """, Long.class, marker, marker + "@example.test");
    }

    private String uid(long id) {
        return "risk-persistence-" + id;
    }

    private long snapshotCount() {
        return jdbc.queryForObject("SELECT count(*) FROM safe_return_sessions WHERE id=?", Long.class, sessionId);
    }

    private EventRequest event(String description) {
        return new EventRequest(sessionId, "danger_phrase", description, Instant.parse("2026-11-01T10:00:00Z"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createRequest(UUID id, String token) {
        return post(EVENTS).header("Authorization", "Bearer " + token).contentType("application/json").content("""
                {"sessionId":"%s","eventType":"danger_phrase","description":"risk test","detectedAt":"2026-11-01T10:00:00Z"}
                """.formatted(id));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder statusRequest(long id, String token, String body) {
        return patch(EVENTS + "/{eventId}/status", id).header("Authorization", "Bearer " + token)
                .contentType("application/json").content(body);
    }

    private MockMultipartFile audio() {
        return new MockMultipartFile("file", "fixture.wav", "audio/wav", new byte[] {1, 2, 3});
    }

    private long fileCount() throws Exception {
        try (var paths = Files.list(mediaRoot)) {
            return paths.count();
        }
    }
}
