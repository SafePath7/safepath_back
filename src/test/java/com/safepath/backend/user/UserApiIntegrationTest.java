package com.safepath.backend.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.config.FirebaseConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "SAFEPATH_PHONE_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "SAFEPATH_PHONE_HASH_KEY=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class UserApiIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FirebaseConfig firebaseConfig;
    @MockitoBean FirebaseTokenService tokens;
    @MockitoBean FirebaseAccountService accounts;
    private final String uid = "test-" + UUID.randomUUID();
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void cleanup() { jdbc.update("DELETE FROM users WHERE firebase_uid = ?", uid); }

    private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void loginRegistrationAndMeAreConsistentAndIdempotent() throws Exception {
        when(tokens.verifyIdToken("valid")).thenReturn(uid);
        when(accounts.getAccount(uid)).thenReturn(new FirebaseAccountService.Account("Google Name", uid + "@example.com", "+821012345678"));
        var before = request("POST", "/api/auth/login", "valid", null);
        assertEquals("SIGNUP_REQUIRED", json.readTree(before.body()).get("status").asText());
        assertEquals(404, request("GET", "/api/users/me", "valid", null).statusCode());
        var created = request("POST", "/api/users/me/registration", "valid", "{\"name\":\"홍길동\"}");
        assertEquals(201, created.statusCode());
        long id = json.readTree(created.body()).get("userId").asLong();
        var repeated = request("POST", "/api/users/me/registration", "valid", "{\"name\":\"다른 이름\"}");
        assertEquals(200, repeated.statusCode());
        assertEquals(id, json.readTree(repeated.body()).get("userId").asLong());
        var login = request("POST", "/api/auth/login", "valid", null);
        assertEquals(200, login.statusCode());
        JsonNode loginBody = json.readTree(login.body());
        assertEquals("REGISTERED", loginBody.get("status").asText());
        assertEquals(id, loginBody.get("userId").asLong());
        var me = request("GET", "/api/users/me", "valid", null);
        assertEquals(200, me.statusCode());
        JsonNode meBody = json.readTree(me.body());
        assertEquals(id, meBody.get("userId").asLong());
        assertEquals("홍길동", meBody.get("name").asText());
        assertEquals(uid + "@example.com", meBody.get("email").asText());
        assertEquals("+821012345678", meBody.get("phoneNumber").asText());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM users WHERE firebase_uid = ?", Integer.class, uid));
        String stored = jdbc.queryForObject("SELECT phone_encrypted FROM users WHERE firebase_uid = ?", String.class, uid);
        assertFalse(stored.contains("+821012345678"));
        verify(accounts, times(1)).getAccount(uid);
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        when(tokens.verifyIdToken(anyString())).thenThrow(new BadCredentialsException("invalid"));
        for (String path : new String[]{"/api/users/me", "/api/users/me/registration"}) {
            String method = path.endsWith("registration") ? "POST" : "GET";
            assertEquals(401, request(method, path, null, null).statusCode());
            assertEquals(401, request(method, path, "invalid", null).statusCode());
        }
        var otherApi = request("GET", "/api/example", "invalid", null);
        assertEquals(401, otherApi.statusCode());
        assertTrue(otherApi.body().isEmpty());
    }

    @Test
    void validatesNameAndAccount() throws Exception {
        when(tokens.verifyIdToken("valid")).thenReturn(uid);
        when(accounts.getAccount(uid)).thenReturn(null);
        assertEquals(422, request("POST", "/api/users/me/registration", "valid", "{}").statusCode());
        when(accounts.getAccount(uid)).thenReturn(new FirebaseAccountService.Account(null, uid + "@example.com", "+821012345679"));
        assertEquals(400, request("POST", "/api/users/me/registration", "valid", "{}").statusCode());
        assertEquals(400, request("POST", "/api/users/me/registration", "valid", "{\"name\":\"" + "a".repeat(101) + "\"}").statusCode());
        when(accounts.getAccount(uid)).thenReturn(new FirebaseAccountService.Account("Google Name", uid + "@example.com", "+821012345679"));
        assertEquals(201, request("POST", "/api/users/me/registration", "valid", "{\"name\":\"  \"}").statusCode());
        assertEquals("Google Name", json.readTree(request("GET", "/api/users/me", "valid", null).body()).get("name").asText());
    }

    @Test
    void rejectsOtherAccountsWithSameEmailOrPhone() throws Exception {
        String otherUid = uid + "-other";
        when(tokens.verifyIdToken("valid")).thenReturn(uid);
        when(tokens.verifyIdToken("other")).thenReturn(otherUid);
        when(accounts.getAccount(uid)).thenReturn(new FirebaseAccountService.Account("First", uid + "@example.com", "+821012345670"));
        assertEquals(201, request("POST", "/api/users/me/registration", "valid", "{}").statusCode());
        when(accounts.getAccount(otherUid)).thenReturn(new FirebaseAccountService.Account("Other", uid + "@example.com", "+821012345671"));
        assertEquals(409, request("POST", "/api/users/me/registration", "other", "{}").statusCode());
        when(accounts.getAccount(otherUid)).thenReturn(new FirebaseAccountService.Account("Other", otherUid + "@example.com", "+821012345670"));
        assertEquals(409, request("POST", "/api/users/me/registration", "other", "{}").statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM users WHERE firebase_uid = ?", Integer.class, otherUid));
    }

    @Test
    void updatesOwnProfileAndOnlyAcceptsTheFirebaseLinkedPhone() throws Exception {
        when(tokens.verifyIdToken("valid")).thenReturn(uid);
        assertEquals(401, request("PATCH", "/api/users/me", null, "{\"name\":\"New name\"}").statusCode());
        assertEquals(404, request("PATCH", "/api/users/me", "valid", "{\"name\":\"New name\"}").statusCode());
        when(accounts.getAccount(uid)).thenReturn(new FirebaseAccountService.Account(
                "Original name", uid + "@example.com", "+16505550100"));
        assertEquals(201, request("POST", "/api/users/me/registration", "valid", "{}").statusCode());

        jdbc.update("UPDATE users SET updated_at = TIMESTAMP '2000-01-01' WHERE firebase_uid = ?", uid);
        assertEquals(400, request("PATCH", "/api/users/me", "valid", "{\"name\":\"  \"}").statusCode());
        assertEquals(204, request("PATCH", "/api/users/me", "valid", "{\"name\":\" New name \"}").statusCode());
        assertTrue(jdbc.queryForObject(
                "SELECT updated_at > TIMESTAMP '2000-01-01' FROM users WHERE firebase_uid = ?", Boolean.class, uid));

        when(accounts.getLinkedPhoneNumber(uid)).thenReturn("+16505550101");
        assertEquals(503, request("PATCH", "/api/users/me/phone", "valid",
                "{\"phoneNumber\":\"+16505550102\"}").statusCode());
        var before = json.readTree(request("GET", "/api/users/me", "valid", null).body());
        assertEquals("+16505550100", before.get("phoneNumber").asText());
        String oldHash = jdbc.queryForObject("SELECT phone_hash FROM users WHERE firebase_uid = ?", String.class, uid);
        jdbc.update("UPDATE users SET updated_at = TIMESTAMP '2000-01-01' WHERE firebase_uid = ?", uid);

        assertEquals(503, request("PATCH", "/api/users/me/phone", "valid",
                "{\"phoneNumber\":\"+16505550101\"}").statusCode());
        var profile = json.readTree(request("GET", "/api/users/me", "valid", null).body());
        assertEquals("New name", profile.get("name").asText());
        assertEquals("+16505550100", profile.get("phoneNumber").asText());
        assertEquals(oldHash, jdbc.queryForObject("SELECT phone_hash FROM users WHERE firebase_uid = ?", String.class, uid));
        assertFalse(jdbc.queryForObject(
                "SELECT updated_at > TIMESTAMP '2000-01-01' FROM users WHERE firebase_uid = ?", Boolean.class, uid));
    }

    @Test
    void withdrawsOnlyTheCurrentUserAndTheirSessions() throws Exception {
        String guardianUid = uid + "-guardian";
        UUID sessionId = UUID.randomUUID();
        when(tokens.verifyIdToken("valid")).thenReturn(uid);
        when(tokens.verifyIdToken("guardian")).thenReturn(guardianUid);
        when(accounts.getAccount(uid)).thenReturn(new FirebaseAccountService.Account(
                "Member", uid + "@example.com", "+16505550103"));
        when(accounts.getAccount(guardianUid)).thenReturn(new FirebaseAccountService.Account(
                "Guardian", guardianUid + "@example.com", "+16505550104"));
        try {
            var member = request("POST", "/api/users/me/registration", "valid", "{}");
            assertEquals(201, member.statusCode());
            var guardian = request("POST", "/api/users/me/registration", "guardian", "{}");
            assertEquals(201, guardian.statusCode());
            long userId = json.readTree(member.body()).get("userId").asLong();
            long guardianId = json.readTree(guardian.body()).get("userId").asLong();

            jdbc.update("INSERT INTO guardian_registrations (user_id, guardian_id) VALUES (?, ?)", userId, guardianId);
            jdbc.update("""
                    INSERT INTO safe_return_sessions (id, user_id, status, started_at)
                    VALUES (?, ?, 'ENDED', CURRENT_TIMESTAMP)
                    """, sessionId, userId);
            Long eventId = jdbc.queryForObject("""
                    INSERT INTO risk_events (session_id, event_type, description, detected_at)
                    VALUES (?, 'TEST', 'test', CURRENT_TIMESTAMP) RETURNING id
                    """, Long.class, sessionId);
            jdbc.update("INSERT INTO risk_event_medias (event_id, media_type, storage_url) VALUES (?, 'AUDIO', ?)",
                    eventId, "https://storage.example/file");

            assertEquals(401, request("DELETE", "/api/users/me", null, null).statusCode());
            assertEquals(503, request("DELETE", "/api/users/me", "valid", null).statusCode());
            assertEquals(200, request("GET", "/api/users/me", "valid", null).statusCode());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM guardian_registrations WHERE user_id = ?",
                    Integer.class, userId));
            jdbc.update("UPDATE risk_event_medias SET storage_url = NULL WHERE event_id = ?", eventId);
            assertEquals(204, request("DELETE", "/api/users/me", "valid", null).statusCode());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM safe_return_sessions WHERE id = ?", Integer.class, sessionId));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM risk_events WHERE id = ?", Integer.class, eventId));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM risk_event_medias WHERE event_id = ?", Integer.class, eventId));
            assertEquals(204, request("DELETE", "/api/users/me", "valid", null).statusCode());
            assertEquals(404, request("GET", "/api/users/me", "valid", null).statusCode());
            assertEquals(200, request("GET", "/api/users/me", "guardian", null).statusCode());
            var login = request("POST", "/api/auth/login", "valid", null);
            assertEquals(200, login.statusCode());
            assertEquals("SIGNUP_REQUIRED", json.readTree(login.body()).get("status").asText());
        } finally {
            jdbc.update("DELETE FROM risk_event_medias WHERE event_id IN (SELECT id FROM risk_events WHERE session_id = ?)", sessionId);
            jdbc.update("DELETE FROM risk_events WHERE session_id = ?", sessionId);
            jdbc.update("DELETE FROM safe_return_sessions WHERE id = ?", sessionId);
            jdbc.update("""
                    DELETE FROM guardian_registrations
                    WHERE user_id IN (SELECT id FROM users WHERE firebase_uid IN (?, ?))
                       OR guardian_id IN (SELECT id FROM users WHERE firebase_uid IN (?, ?))
                    """, uid, guardianUid, uid, guardianUid);
            jdbc.update("DELETE FROM users WHERE firebase_uid = ?", guardianUid);
        }
    }
}
