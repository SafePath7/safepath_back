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
}
