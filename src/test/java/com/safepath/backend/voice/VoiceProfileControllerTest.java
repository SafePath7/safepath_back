package com.safepath.backend.voice;

import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.config.FirebaseConfig;
import com.safepath.backend.user.UserService;
import com.safepath.backend.voice.dto.VoiceProfileResult;
import com.safepath.backend.voice.exception.VoiceProfileException;
import com.safepath.backend.voice.exception.VoiceProfileException.Reason;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class VoiceProfileControllerTest {
    private static final String URL = "/api/voice-profiles/me";
    private static final Instant A_TIME = Instant.parse("2026-11-19T10:00:00Z");
    private static final Instant B_TIME = Instant.parse("2026-11-19T11:00:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean FirebaseConfig firebaseConfig;
    @MockitoBean FirebaseTokenService tokens;
    @MockitoBean UserService users;
    @MockitoBean VoiceProfileService profiles;

    @BeforeEach
    void authenticateMembers() {
        when(tokens.verifyIdToken("token-a")).thenReturn("uid-a");
        when(tokens.verifyIdToken("token-b")).thenReturn("uid-b");
        when(users.findUserIdByFirebaseUid("uid-a")).thenReturn(Optional.of(7L));
        when(users.findUserIdByFirebaseUid("uid-b")).thenReturn(Optional.of(8L));
    }

    @Test
    void returnsOnlyPublicFieldsForRegisteredMember() throws Exception {
        when(profiles.getVoiceProfile(7L)).thenReturn(profile(7L, A_TIME));

        mvc.perform(get(URL).header("Authorization", "Bearer token-a"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"registered":true,"consentedAt":"2026-11-19T10:00:00Z",
                         "createdAt":"2026-11-19T10:00:00Z","updatedAt":"2026-11-19T10:00:00Z"}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void returnsOnlyRegistrationFlagWhenOtherMemberHasProfile() throws Exception {
        when(profiles.getVoiceProfile(7L)).thenThrow(new VoiceProfileException(Reason.NOT_FOUND, "missing"));
        when(profiles.getVoiceProfile(8L)).thenReturn(profile(8L, B_TIME));

        mvc.perform(get(URL).header("Authorization", "Bearer token-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consentedAt").value(B_TIME.toString()));
        mvc.perform(get(URL).param("userId", "8").param("firebaseUid", "uid-b")
                        .header("Authorization", "Bearer token-a"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"registered\":false}", org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void missingTokenReturnsUnauthorized() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles);
    }

    @Test
    void invalidTokenReturnsUnauthorized() throws Exception {
        when(tokens.verifyIdToken("invalid")).thenThrow(new BadCredentialsException("invalid"));
        mvc.perform(get(URL).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles);
    }

    @Test
    void authenticatedNonMemberReturnsNotFound() throws Exception {
        when(tokens.verifyIdToken("unknown")).thenReturn("unknown-uid");
        when(users.findUserIdByFirebaseUid("unknown-uid")).thenReturn(Optional.empty());

        mvc.perform(get(URL).header("Authorization", "Bearer unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verifyNoInteractions(profiles);
    }

    @Test
    void databaseFailureReturnsSanitizedServerError() throws Exception {
        when(profiles.getVoiceProfile(7L)).thenThrow(
                new VoiceProfileException(Reason.DATABASE_ERROR, "private SQL payload"));

        mvc.perform(get(URL).header("Authorization", "Bearer token-a"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.registered").doesNotExist())
                .andExpect(content().string(not(containsString("private SQL payload"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE"})
    void writeMethodsAreNotExposed(String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), URL).header("Authorization", "Bearer token-a"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(request(HttpMethod.valueOf(method), "/api/voice-profiles")
                        .header("Authorization", "Bearer token-a"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(profiles);
    }

    private VoiceProfileResult profile(long userId, Instant time) {
        return new VoiceProfileResult(userId, new float[] {0.5f}, "private-audio-path",
                0.25, 145.0, "private-model", time, time, time,
                new JsonMapper().readTree("{\"internal\":true}"), "private-urgent-audio-path");
    }
}
