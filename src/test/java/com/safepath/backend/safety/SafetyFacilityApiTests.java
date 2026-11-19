package com.safepath.backend.safety;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.config.FirebaseConfig;

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

@SpringBootTest(properties = {
        "safepath.security-light.sync.enabled=false",
        "safepath.cctv.sync.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class SafetyFacilityApiTests {
    private static final String URL = "/api/safety-facilities";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FirebaseConfig firebaseConfig;
    @MockitoBean FirebaseTokenService tokens;

    @BeforeEach
    void setup() {
        jdbc.execute("CREATE TEMP TABLE safety_facilities (LIKE public.safety_facilities INCLUDING ALL) ON COMMIT DROP");
        facility(37.56, 126.97, "Seoul", "POLICE");
        facility(37.57, 126.98, "Seoul", "SHELTER");
        facility(37.58, 126.99, "Seoul", "HOSPITAL");
        facility(37.70, 127.10, "Outside", "POLICE");
    }

    @Test
    void returnsEveryFacilityInBoundsAtCloseKakaoMapLevel() throws Exception {
        mvc.perform(request(3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.facilities.length()").value(3))
                .andExpect(jsonPath("$.facilities[0].facilityType").value("POLICE"))
                .andExpect(jsonPath("$.facilities[2].address").value("Seoul"));
        verifyNoInteractions(tokens);
    }

    @Test
    void returnsClustersAtDistantKakaoMapLevel() throws Exception {
        mvc.perform(request(7)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("CLUSTER"))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.clusters.length()").value(1))
                .andExpect(jsonPath("$.clusters[0].count").value(3))
                .andExpect(jsonPath("$.facilities.length()").value(0));
    }

    @Test
    void rejectsInvalidBoundsWithoutAuthentication() throws Exception {
        mvc.perform(get(URL).param("zoom", "3").param("southWestLat", "38").param("southWestLng", "126")
                .param("northEastLat", "37").param("northEastLng", "127"))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(int zoom) {
        return get(URL).param("zoom", Integer.toString(zoom)).param("southWestLat", "37.55")
                .param("southWestLng", "126.95").param("northEastLat", "37.60")
                .param("northEastLng", "127.00");
    }

    private void facility(double latitude, double longitude, String address, String type) {
        jdbc.update("INSERT INTO safety_facilities (latitude,longitude,address,facility_type) VALUES (?,?,?,?)",
                latitude, longitude, address, type);
    }
}
