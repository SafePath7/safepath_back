package com.safepath.backend.safety.admin;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.common.auth.FirebaseAdminRoleLookup;
import com.safepath.backend.config.FirebaseConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "safepath.security-light.sync.enabled=false",
        "safepath.cctv.sync.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class SafetyFacilityAdminApiTests {
    private static final String URL = "/api/admin/safety-facilities";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FirebaseConfig firebaseConfig;
    @MockitoBean FirebaseTokenService tokens;
    @MockitoBean FirebaseAdminRoleLookup roles;

    @BeforeEach
    void setup() {
        jdbc.execute("CREATE TEMP TABLE users (LIKE public.users INCLUDING ALL) ON COMMIT DROP");
        jdbc.execute("CREATE TEMP TABLE safety_facilities (LIKE public.safety_facilities INCLUDING ALL) ON COMMIT DROP");
        member("admin");
        member("user");
        when(tokens.verifyIdToken(anyString())).thenAnswer(call -> call.getArgument(0));
        when(roles.isAdmin("admin")).thenReturn(true);
        when(roles.isAdmin("user")).thenReturn(false);
    }

    @Test
    void adminCanCreateReadUpdateListAndDeleteFacility() throws Exception {
        mvc.perform(post(URL).header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON)
                .content(request("35.83436", "128.48410", "대구광역시 달서구 대천동 900", "CCTV")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.address").value("대구광역시 달서구 대천동 900"))
                .andExpect(jsonPath("$.facilityType").value("CCTV"))
                .andExpect(jsonPath("$.source").value("ADMIN"));

        long facilityId = jdbc.queryForObject("SELECT id FROM safety_facilities ORDER BY id DESC LIMIT 1", Long.class);
        mvc.perform(get(URL).header("Authorization", "Bearer admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.facilities[0].id").value(facilityId));
        mvc.perform(get(URL + "/" + facilityId).header("Authorization", "Bearer admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(facilityId));

        mvc.perform(patch(URL + "/" + facilityId).header("Authorization", "Bearer admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("35.83500", "128.48500", "대구광역시 달서구 호산동 1", "EMERGENCY_CALL_BOX")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(35.83500))
                .andExpect(jsonPath("$.facilityType").value("EMERGENCY_CALL_BOX"));

        mvc.perform(delete(URL + "/" + facilityId).header("Authorization", "Bearer admin"))
                .andExpect(status().isNoContent());
        mvc.perform(get(URL + "/" + facilityId).header("Authorization", "Bearer admin"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonAdminAndAnonymousUsersCannotManageFacilities() throws Exception {
        mvc.perform(get(URL).header("Authorization", "Bearer user")).andExpect(status().isForbidden());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                .content(request("35.83436", "128.48410", "대구", "CCTV")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(URL).header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"longitude\":128.48410,\"address\":\"대구\",\"facilityType\":\"CCTV\"}"))
                .andExpect(status().isBadRequest());
    }

    private long member(String uid) {
        return jdbc.queryForObject("INSERT INTO users (firebase_uid,name,phone_hash,phone_encrypted,email) "
                + "VALUES (?,'test',?,'test',?) RETURNING id", Long.class, uid, uid, uid + "@example.test");
    }

    private String request(String latitude, String longitude, String address, String facilityType) {
        return """
                {"latitude":%s,"longitude":%s,"address":"%s","facilityType":"%s"}
                """.formatted(latitude, longitude, address, facilityType);
    }
}
