package com.safepath.backend.guardian;

import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.config.FirebaseConfig;
import com.safepath.backend.guardianrisk.FirebaseAdminRoleLookup;

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
class GuardianRegistrationListApiTests {
    private static final String URL = "/api/guardian-registrations";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FirebaseConfig firebaseConfig;
    @MockitoBean FirebaseTokenService tokens;
    @MockitoBean FirebaseAdminRoleLookup roles;
    long user;
    long guardian;
    long other;
    long pending;
    long accepted;

    @BeforeEach
    void setup() {
        for (String table : new String[] {"users", "guardian_registrations"}) {
            jdbc.execute("CREATE TEMP TABLE " + table + " (LIKE public." + table + " INCLUDING ALL) ON COMMIT DROP");
        }
        user = member("user", "이용자");
        guardian = member("guardian", "보호자");
        other = member("other", "다른사람");
        // user -> guardian is still pending; guardian -> other is accepted, so "guardian"
        // is on the guardian side of one registration and the user side of another.
        pending = registration(user, guardian, "PENDING");
        accepted = registration(guardian, other, "ACCEPTED");
        when(tokens.verifyIdToken(anyString())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void guardianFindsTheRequestSentToThemWithTheRequesterName() throws Exception {
        mvc.perform(get(URL).param("role", "GUARDIAN").header("Authorization", "Bearer guardian"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(pending))
                .andExpect(jsonPath("$[0].userId").value(user))
                .andExpect(jsonPath("$[0].userName").value("이용자"))
                .andExpect(jsonPath("$[0].guardianName").value("보호자"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].canViewLocation").value(false));
    }

    @Test
    void roleSelectsTheSideAndNoRoleReturnsBothNewestFirst() throws Exception {
        mvc.perform(get(URL).param("role", "USER").header("Authorization", "Bearer guardian"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(accepted));
        mvc.perform(get(URL).header("Authorization", "Bearer guardian"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(accepted))
                .andExpect(jsonPath("$[1].id").value(pending));
    }

    @Test
    void statusFilterNarrowsTheList() throws Exception {
        mvc.perform(get(URL).param("status", "ACCEPTED").header("Authorization", "Bearer guardian"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(accepted));
        mvc.perform(get(URL).param("role", "GUARDIAN").param("status", "ACCEPTED")
                        .header("Authorization", "Bearer guardian"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void membersNeverSeeRegistrationsTheyAreNotPartOf() throws Exception {
        mvc.perform(get(URL).header("Authorization", "Bearer user"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(pending));
        mvc.perform(get(URL).header("Authorization", "Bearer other"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(accepted));
    }

    @Test
    void invalidFilterOrMissingTokenIsRejected() throws Exception {
        mvc.perform(get(URL).param("role", "WRONG").header("Authorization", "Bearer user"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(URL).param("status", "WRONG").header("Authorization", "Bearer user"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    private long member(String uid, String name) {
        return jdbc.queryForObject("INSERT INTO users (firebase_uid,name,phone_hash,phone_encrypted,email) VALUES (?,?,?,'test',?) RETURNING id",
                Long.class, uid, name, uid, uid + "@example.test");
    }

    private long registration(long userId, long guardianId, String status) {
        return jdbc.queryForObject("INSERT INTO guardian_registrations (user_id,guardian_id,status) VALUES (?,?,?) RETURNING id",
                Long.class, userId, guardianId, status);
    }
}
