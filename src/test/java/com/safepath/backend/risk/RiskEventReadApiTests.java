package com.safepath.backend.risk;

import java.util.UUID;

import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.config.FirebaseConfig;
import com.safepath.backend.common.auth.FirebaseAdminRoleLookup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL/HTTP mappings with only the Firebase calls replaced. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class RiskEventReadApiTests {
    private static final String URL = "/api/risk-events";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FirebaseConfig firebaseConfig;
    @MockitoBean FirebaseTokenService tokens;
    @MockitoBean FirebaseAdminRoleLookup roles;
    long owner;
    UUID firstSession = UUID.randomUUID();
    UUID secondSession = UUID.randomUUID();
    long oldEvent;
    long newEvent;
    long otherSessionEvent;

    @BeforeEach
    void setup() {
        for (String table : new String[] {"users", "guardian_registrations", "safe_return_sessions", "risk_events"}) {
            jdbc.execute("CREATE TEMP TABLE " + table + " (LIKE public." + table + " INCLUDING ALL) ON COMMIT DROP");
        }
        owner = member("owner");
        session(firstSession, owner);
        session(secondSession, owner);
        oldEvent = event(firstSession, "2026-11-01 10:00:00");
        newEvent = event(firstSession, "2026-11-03 10:00:00");
        otherSessionEvent = event(secondSession, "2026-11-02 10:00:00");
        // Guardians differ only in registration state and the STATUS flag.
        guardian("guardian", "ACCEPTED", 0, 1);
        guardian("location-only", "ACCEPTED", 1, 0);
        guardian("pending", "PENDING", 1, 1);
        guardian("suspended", "SUSPENDED", 1, 1);
        member("stranger");
        member("admin");
        when(tokens.verifyIdToken(anyString())).thenAnswer(call -> call.getArgument(0));
        when(roles.isAdmin("admin")).thenReturn(true);
    }

    @Test
    void ownerListsOwnEventsMostRecentFirst() throws Exception {
        mvc.perform(get(URL).header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(newEvent))
                .andExpect(jsonPath("$[1].id").value(otherSessionEvent))
                .andExpect(jsonPath("$[2].id").value(oldEvent))
                .andExpect(jsonPath("$[0].userId").value(owner))
                .andExpect(jsonPath("$[0].sessionId").value(firstSession.toString()))
                .andExpect(jsonPath("$[0].eventType").value("SOS"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].detectedAt").value("2026-11-03T10:00:00Z"));
    }

    @Test
    void sessionAndLimitNarrowTheList() throws Exception {
        mvc.perform(get(URL).param("sessionId", secondSession.toString()).header("Authorization", "Bearer owner"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(otherSessionEvent));
        mvc.perform(get(URL).param("limit", "2").header("Authorization", "Bearer owner"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].id").value(otherSessionEvent));
    }

    @Test
    void acceptedGuardianWithStatusPermissionReadsTheUsersEvents() throws Exception {
        mvc.perform(get(URL).param("userId", Long.toString(owner)).header("Authorization", "Bearer guardian"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get(URL + "/" + newEvent).header("Authorization", "Bearer guardian"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(newEvent))
                .andExpect(jsonPath("$.userId").value(owner));
        // Without userId a guardian sees their own (empty) list, never the user's.
        mvc.perform(get(URL).header("Authorization", "Bearer guardian"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anyoneWithoutAnAcceptedStatusPermissionIsForbidden() throws Exception {
        for (String uid : new String[] {"location-only", "pending", "suspended", "stranger"}) {
            mvc.perform(get(URL).param("userId", Long.toString(owner)).header("Authorization", "Bearer " + uid))
                    .andExpect(status().isForbidden());
            mvc.perform(get(URL + "/" + newEvent).header("Authorization", "Bearer " + uid))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void ownerAndAdminReadASingleEvent() throws Exception {
        mvc.perform(get(URL + "/" + oldEvent).header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(oldEvent));
        mvc.perform(get(URL + "/" + oldEvent).header("Authorization", "Bearer admin"))
                .andExpect(status().isOk());
        mvc.perform(get(URL).param("userId", Long.toString(owner)).header("Authorization", "Bearer admin"))
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void missingEventInvalidParametersAndMissingTokenAreRejected() throws Exception {
        mvc.perform(get(URL + "/999999999").header("Authorization", "Bearer owner"))
                .andExpect(status().isNotFound());
        mvc.perform(get(URL).param("limit", "0").header("Authorization", "Bearer owner"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(URL).param("limit", "101").header("Authorization", "Bearer owner"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(URL).param("sessionId", "not-a-uuid").header("Authorization", "Bearer owner"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(URL).param("userId", "0").header("Authorization", "Bearer owner"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    private long member(String uid) {
        return jdbc.queryForObject("INSERT INTO users (firebase_uid,name,phone_hash,phone_encrypted,email) VALUES (?,'test',?,'test',?) RETURNING id",
                Long.class, uid, uid, uid + "@example.test");
    }

    private void guardian(String uid, String status, int location, int viewStatus) {
        jdbc.update("INSERT INTO guardian_registrations (user_id,guardian_id,status,can_view_location,can_view_status) VALUES (?,?,?,?,?)",
                owner, member(uid), status, location, viewStatus);
    }

    private void session(UUID id, long userId) {
        jdbc.update("INSERT INTO safe_return_sessions (id,user_id,status,started_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP)",
                id, userId);
    }

    private long event(UUID session, String detectedAt) {
        return jdbc.queryForObject("INSERT INTO risk_events (session_id,event_type,description,detected_at) VALUES (?,'SOS','read test',?::timestamp) RETURNING id",
                Long.class, session, detectedAt);
    }
}
