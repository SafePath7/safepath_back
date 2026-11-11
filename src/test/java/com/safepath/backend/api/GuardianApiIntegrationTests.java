package com.safepath.backend.api;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GuardianApiIntegrationTests {
    private static final IsolatedTestDatabase DATABASE = new IsolatedTestDatabase();
    private static final String REGISTRATIONS = "/api/guardian-registrations";

    @DynamicPropertySource
    static void configureStorage(DynamicPropertyRegistry registry) {
        DATABASE.configure(registry);
    }

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FirebaseIdTokenVerifier verifier;

    long owner;
    long guardian;
    long other;
    long admin;

    @BeforeEach
    void setup() {
        DATABASE.assertIsolated(jdbc);
        owner = user("owner");
        guardian = user("guardian");
        other = user("other");
        admin = user("admin");
        when(verifier.verify(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            if (!java.util.Set.of("owner", "guardian", "other", "admin").contains(token)) {
                throw new IllegalArgumentException("invalid token");
            }
            return new VerifiedIdentity(token, "admin".equals(token));
        });
    }

    @Test
    void authenticationCannotBeBypassedWithRawUidOrAdminHeader() throws Exception {
        mvc.perform(post(REGISTRATIONS).contentType("application/json").content("{\"guardianId\":" + guardian + "}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(REGISTRATIONS).header("Authorization", "Bearer invalid")
                .contentType("application/json").content("{\"guardianId\":" + guardian + "}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/guardian-registrations/1/suspend").header("Authorization", "Bearer owner")
                .header("X-Admin", "true")).andExpect(status().isForbidden());
        mvc.perform(get("/api/guardian-registrations/1/permissions/check?permissionType=LOCATION").header("Authorization", "Basic invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationTakesOwnerAndInitialStateFromServer() throws Exception {
        mvc.perform(post(REGISTRATIONS).header("Authorization", "Bearer owner").contentType("application/json")
                .content("{\"guardianId\":" + guardian + ",\"userId\":" + other + ",\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("userId").value(owner))
                .andExpect(jsonPath("status").value("PENDING"))
                .andExpect(jsonPath("requestedAt").isNotEmpty()).andExpect(jsonPath("canViewLocation").value(false));
        mvc.perform(post(REGISTRATIONS).header("Authorization", "Bearer owner").contentType("application/json")
                .content("{\"guardianId\":" + guardian + "}")).andExpect(status().isConflict());
        assertThat(count("guardian_registrations")).isEqualTo(1);
    }

    @Test
    void registrationRejectsSelfMissingGuardianAndInvalidId() throws Exception {
        for (String body : new String[] {"{\"guardianId\":" + owner + "}", "{\"guardianId\":0}", "{}"}) {
            mvc.perform(post(REGISTRATIONS).header("Authorization", "Bearer owner").contentType("application/json")
                    .content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post(REGISTRATIONS).header("Authorization", "Bearer owner").contentType("application/json")
                .content("{\"guardianId\":9223372036854775807}")).andExpect(status().isNotFound());
    }

    @Test
    void onlyInvitedGuardianCanAcceptPendingRegistration() throws Exception {
        long id = request();
        for (String token : new String[] {"owner", "other", "admin"}) {
            mvc.perform(patch(path(id, "accept")).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(patch(path(id, "accept")).header("Authorization", "Bearer guardian"))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("ACCEPTED"))
                .andExpect(jsonPath("acceptedAt").isNotEmpty());
        mvc.perform(patch(path(id, "accept")).header("Authorization", "Bearer guardian"))
                .andExpect(status().isConflict());
        mvc.perform(patch(path(id, "reject")).header("Authorization", "Bearer guardian"))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectionAndRevocationChangeOnlyTheSpecifiedRegistrations() throws Exception {
        long id = request();
        mvc.perform(patch(path(id, "reject")).header("Authorization", "Bearer owner"))
                .andExpect(status().isForbidden());
        mvc.perform(patch(path(id, "reject")).header("Authorization", "Bearer guardian"))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("REJECTED"));
        mvc.perform(post(REGISTRATIONS).header("Authorization", "Bearer owner").contentType("application/json")
                .content("{\"guardianId\":" + guardian + "}")).andExpect(status().isConflict());
        mvc.perform(post(REGISTRATIONS).header("Authorization", "Bearer owner").contentType("application/json")
                .content("{\"guardianId\":" + other + "}")).andExpect(status().isCreated());
        long secondId = jdbc.queryForObject("SELECT id FROM guardian_registrations WHERE guardian_id=?", Long.class, other);
        mvc.perform(patch(path(secondId, "revoke")).header("Authorization", "Bearer guardian"))
                .andExpect(status().isForbidden());
        mvc.perform(patch(path(secondId, "revoke")).header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("REVOKED"));
        assertThat(count("guardian_registrations")).isEqualTo(2);
    }

    @Test
    void permissionsAreOwnerControlledStoredAsSmallintsAndCheckedByType() throws Exception {
        long id = accepted();
        String permissions = "{\"canViewLocation\":true,\"canViewStatus\":false,\"canViewPersonalInfo\":true}";
        mvc.perform(patch(path(id, "permissions")).header("Authorization", "Bearer guardian")
                .contentType("application/json").content(permissions)).andExpect(status().isForbidden());
        mvc.perform(patch(path(id, "permissions")).header("Authorization", "Bearer owner")
                .contentType("application/json").content(permissions))
                .andExpect(status().isOk()).andExpect(jsonPath("canViewLocation").value(true));
        assertThat(jdbc.queryForObject("SELECT can_view_location FROM guardian_registrations WHERE id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT can_view_status FROM guardian_registrations WHERE id=?", Integer.class, id)).isZero();
        for (String type : new String[] {"LOCATION", "STATUS", "PERSONAL_INFO"}) {
            mvc.perform(get(path(id, "permissions/check")).header("Authorization", "Bearer guardian")
                    .param("permissionType", type)).andExpect(status().isOk())
                    .andExpect(jsonPath("allowed").value(!type.equals("STATUS")));
        }
        mvc.perform(get(path(id, "permissions/check")).header("Authorization", "Bearer other")
                .param("permissionType", "LOCATION")).andExpect(status().isForbidden());
        mvc.perform(get(path(id, "permissions/check")).header("Authorization", "Bearer owner")
                .param("permissionType", "INVALID")).andExpect(status().isBadRequest());
        mvc.perform(patch(path(id, "permissions")).header("Authorization", "Bearer owner")
                .contentType("application/json").content("{\"canViewLocation\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyAdminCanSuspendAndSuspensionImmediatelyDisablesPermission() throws Exception {
        long id = accepted();
        jdbc.update("UPDATE guardian_registrations SET can_view_location=1 WHERE id=?", id);
        mvc.perform(patch(path(id, "suspend")).header("Authorization", "Bearer owner"))
                .andExpect(status().isForbidden());
        mvc.perform(patch(path(id, "suspend")).header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("SUSPENDED"));
        mvc.perform(get(path(id, "permissions/check")).header("Authorization", "Bearer guardian")
                .param("permissionType", "LOCATION")).andExpect(status().isOk())
                .andExpect(jsonPath("allowed").value(false));
        mvc.perform(patch(path(id, "revoke")).header("Authorization", "Bearer guardian"))
                .andExpect(status().isOk());
        mvc.perform(patch(path(id, "revoke")).header("Authorization", "Bearer admin"))
                .andExpect(status().isConflict());
    }

    @Test
    void permissionMutationRequiresAcceptedState() throws Exception {
        long id = request();
        mvc.perform(patch(path(id, "permissions")).header("Authorization", "Bearer owner").contentType("application/json")
                .content("{\"canViewLocation\":true,\"canViewStatus\":true,\"canViewPersonalInfo\":true}"))
                .andExpect(status().isConflict());
    }

    @Test
    void verifiedUidWithoutRegisteredMemberReturns404() throws Exception {
        doReturn(new VerifiedIdentity("not-registered", false)).when(verifier).verify("unknown-user");
        mvc.perform(post(REGISTRATIONS).header("Authorization", "Bearer unknown-user").contentType("application/json")
                .content("{\"guardianId\":" + guardian + "}")).andExpect(status().isNotFound());
        assertThat(count("users")).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"guardian_registrations"})
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
            case "guardian_registrations" -> List.of(
                    "id:bigint:NOT NULL:IDENTITY", "user_id:bigint:NOT NULL", "guardian_id:bigint:NOT NULL",
                    "requested_at:timestamp without time zone:NOT NULL", "accepted_at:timestamp without time zone:NULL",
                    "status:character varying(20):NOT NULL", "can_view_location:smallint:NOT NULL",
                    "can_view_status:smallint:NOT NULL", "can_view_personal_info:smallint:NOT NULL",
                    "created_at:timestamp without time zone:NOT NULL", "updated_at:timestamp without time zone:NOT NULL");
            default -> throw new IllegalArgumentException(table);
        };
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void databaseGeneratesIdsAndTheSpecifiedDefaultValues() {
        var registration = jdbc.queryForMap("""
                INSERT INTO guardian_registrations (user_id,guardian_id) VALUES (?,?) RETURNING *
                """, owner, guardian);
        assertThat((Long) registration.get("id")).isPositive();
        assertThat(registration.get("user_id")).isEqualTo(owner);
        assertThat(registration.get("guardian_id")).isEqualTo(guardian);
        assertThat(registration.get("status")).isEqualTo("PENDING");
        assertThat(registration.get("accepted_at")).isNull();
        for (String permission : List.of("can_view_location", "can_view_status", "can_view_personal_info")) {
            assertThat(((Number) registration.get(permission)).intValue()).isZero();
        }
        for (String field : List.of("requested_at", "created_at", "updated_at")) {
            assertThat(registration.get(field)).isNotNull();
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
                  AND constraints.table_name IN ('guardian_registrations')
                """, (rs, row) -> rs.getString("table_name") + "." + rs.getString("column_name")
                        + "->" + rs.getString("target_table") + "." + rs.getString("target_column"));
        assertThat(keys).containsExactlyInAnyOrder(
                "guardian_registrations.user_id->users.id",
                "guardian_registrations.guardian_id->users.id");
    }

    @Test
    void featureExposesExactlyItsSevenSpecifiedEndpoints() {
        var endpoints = mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().getPackageName().equals("com.safepath.backend.guardian"))
                .flatMap(entry -> entry.getKey().getPatternValues().stream().flatMap(path ->
                        entry.getKey().getMethodsCondition().getMethods().stream().map(method -> method + " " + path)))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(endpoints).containsExactlyInAnyOrder(
                "POST /api/guardian-registrations",
                "PATCH /api/guardian-registrations/{registrationId}/accept",
                "PATCH /api/guardian-registrations/{registrationId}/reject",
                "PATCH /api/guardian-registrations/{registrationId}/revoke",
                "PATCH /api/guardian-registrations/{registrationId}/suspend",
                "PATCH /api/guardian-registrations/{registrationId}/permissions",
                "GET /api/guardian-registrations/{registrationId}/permissions/check");
    }

    private long request() throws Exception {
        mvc.perform(post(REGISTRATIONS).header("Authorization", "Bearer owner").contentType("application/json")
                .content("{\"guardianId\":" + guardian + "}")).andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT id FROM guardian_registrations WHERE user_id=? AND guardian_id=?", Long.class, owner, guardian);
    }

    private long accepted() throws Exception {
        long id = request();
        mvc.perform(patch(path(id, "accept")).header("Authorization", "Bearer guardian")).andExpect(status().isOk());
        return id;
    }

    private String path(long id, String action) { return REGISTRATIONS + "/" + id + "/" + action; }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private long user(String uid) {
        return jdbc.queryForObject("""
                INSERT INTO users (firebase_uid,name,phone_hash,phone_encrypted,email)
                VALUES (?,'테스트 사용자',?,'test',?) RETURNING id
                """, Long.class, uid, uid, uid + "@example.test");
    }

    @AfterAll
    static void cleanup(@Autowired JdbcTemplate jdbc) {
        DATABASE.cleanup(jdbc);
    }
}
