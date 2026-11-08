package com.safepath.backend.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import com.safepath.backend.security.FirebaseIdTokenVerifier;
import com.safepath.backend.security.FirebaseIdTokenVerifier.VerifiedIdentity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/** Real HTTP and committed transactions, isolated from public data by a fully migrated schema. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GuardianRuntimeDatabaseTests {
    private static final String SCHEMA = "api_audit_" + UUID.randomUUID().toString().replace("-", "");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.create-schemas", () -> true);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FirebaseIdTokenVerifier verifier;
    long owner;
    long guardian;

    @BeforeEach
    void fixtures() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        String suffix = UUID.randomUUID().toString();
        owner = user("owner-" + suffix);
        guardian = user("guardian-" + suffix);
        user("admin-" + suffix);
        // Only the common verifier boundary is faked; filters, HTTP, storage and PostgreSQL are real.
        doAnswer(invocation -> {
            String token = invocation.getArgument(0);
            if (!Set.of("owner", "guardian", "admin").contains(token)) throw new IllegalArgumentException("invalid token");
            return new VerifiedIdentity(token + "-" + suffix, "admin".equals(token));
        }).when(verifier).verify(anyString());
    }

    @Test
    void guardianWritesCommitThroughHttp() throws Exception {
        expect(request("POST", "/api/guardian-registrations", "owner", "{\"guardianId\":" + guardian + "}"), 201);
        long registration = jdbc.queryForObject("SELECT id FROM guardian_registrations WHERE user_id=?", Long.class, owner);
        assertThat(jdbc.queryForObject("SELECT status FROM guardian_registrations WHERE id=?", String.class, registration))
                .isEqualTo("PENDING");
        String path = "/api/guardian-registrations/" + registration;
        expect(request("PATCH", path + "/accept", "owner", null), 403);
        expect(request("PATCH", path + "/accept", "guardian", null), 200);
        expect(request("PATCH", path + "/permissions", "owner",
                "{\"canViewLocation\":true,\"canViewStatus\":false,\"canViewPersonalInfo\":true}"), 200);
        var permissions = jdbc.queryForMap("SELECT status,accepted_at,can_view_location,can_view_status,can_view_personal_info FROM guardian_registrations WHERE id=?", registration);
        assertThat(permissions.get("status")).isEqualTo("ACCEPTED");
        assertThat(permissions.get("accepted_at")).isNotNull();
        assertThat(((Number) permissions.get("can_view_location")).intValue()).isEqualTo(1);
        assertThat(((Number) permissions.get("can_view_status")).intValue()).isZero();
        assertThat(((Number) permissions.get("can_view_personal_info")).intValue()).isEqualTo(1);
        var check = request("GET", path + "/permissions/check?permissionType=LOCATION", "guardian", null);
        expect(check, 200);
        assertThat(check.body()).contains("\"allowed\":true");
        expect(request("PATCH", path + "/revoke", "guardian", null), 200);
        assertThat(jdbc.queryForObject("SELECT status FROM guardian_registrations WHERE id=?", String.class, registration))
                .isEqualTo("REVOKED");
        var revoked = request("GET", path + "/permissions/check?permissionType=LOCATION", "guardian", null);
        expect(revoked, 200);
        assertThat(revoked.body()).contains("\"allowed\":false");
    }

    @Test
    void realForeignKeysAndPermissionChecksRejectInvalidRows() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO guardian_registrations (user_id,guardian_id) VALUES (?,?)", owner, -1L))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO guardian_registrations (user_id,guardian_id) VALUES (?,?)", -1L, guardian))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO guardian_registrations (user_id,guardian_id,can_view_location) VALUES (?,?,2)", owner, guardian))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO guardian_registrations (user_id,guardian_id) VALUES (?,?)", owner, owner))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void realSecurityChainRejectsUnauthenticatedAndNonAdminRequests() throws Exception {
        String path = "/api/guardian-registrations/1/suspend";
        expect(request("PATCH", path, null, null), 401);
        expect(request("PATCH", path, "invalid", null), 401);
        expect(request("PATCH", path, "owner", null), 403);
        assertThat(request("GET", "/error", "admin", null).statusCode()).isIn(401, 403);
    }

    private HttpResponse<String> request(String method, String path, String token, String json) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (json != null) builder.header("Content-Type", "application/json");
        return HTTP.send(builder.method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private long user(String uid) {
        return jdbc.queryForObject("INSERT INTO users (firebase_uid,name,phone_hash,phone_encrypted,email) VALUES (?,'runtime',?,'test',?) RETURNING id",
                Long.class, uid, uid, uid + "@example.test");
    }

    private static void expect(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(status);
    }

    @AfterAll
    static void cleanup(@Autowired JdbcTemplate jdbc) {
        HTTP.close();
        if (!SCHEMA.matches("api_audit_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe test schema");
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }
}
