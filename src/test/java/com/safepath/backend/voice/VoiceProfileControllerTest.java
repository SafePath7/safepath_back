package com.safepath.backend.voice;

import com.safepath.backend.auth.FirebaseAuthenticationFilter;
import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.common.auth.CurrentUser;
import com.safepath.backend.common.auth.FirebaseAdminRoleLookup;
import com.safepath.backend.config.SecurityConfig;
import com.safepath.backend.user.UserService;
import com.safepath.backend.voice.dto.VoiceProfileResult;
import com.safepath.backend.voice.exception.VoiceProfileException;
import com.safepath.backend.voice.exception.VoiceProfileException.Reason;
import com.safepath.backend.voice.security.InternalAiKeyVerifier;
import com.safepath.backend.voice.storage.VoiceAudioStorageException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockPart;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = VoiceProfileController.class, properties = {
        "SAFEPATH_AI_INTERNAL_KEY=synthetic-voice-test-key",
        "spring.jackson.default-property-inclusion=non_null"
})
@Import({SecurityConfig.class, FirebaseAuthenticationFilter.class, CurrentUser.class,
        InternalAiKeyVerifier.class, VoiceProfileFeatureService.class})
@ExtendWith(OutputCaptureExtension.class)
class VoiceProfileControllerTest {
    private static final String URL = "/api/voice-profiles/me";
    private static final String FEATURES_URL = URL + "/features";
    private static final String INTERNAL_KEY = "synthetic-voice-test-key";
    private static final Instant A_TIME = Instant.parse("2026-11-19T10:00:00Z");
    private static final Instant B_TIME = Instant.parse("2026-11-19T11:00:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean FirebaseAdminRoleLookup roles;
    @MockitoBean FirebaseTokenService tokens;
    @MockitoBean UserService users;
    @MockitoBean VoiceProfileService profiles;
    @MockitoBean VoiceProfileRegistrationService registration;

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

    @Test
    void returnsOnlyStoredFeaturesForAuthenticatedOwner(CapturedOutput output) throws Exception {
        when(profiles.getVoiceProfile(7L)).thenReturn(featuresProfile(7L, 0.125f, "{\"samples\":3}"));
        when(profiles.getVoiceProfile(8L)).thenReturn(featuresProfile(8L, 0.875f, null));

        var response = mvc.perform(get(FEATURES_URL).param("userId", "8").param("firebaseUid", "uid-b")
                        .header("Authorization", "Bearer token-a").header("X-Internal-Key", INTERNAL_KEY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        var json = new JsonMapper().readTree(response.getContentAsString());
        assertThat(json.size()).isEqualTo(5);
        assertThat(json.required("speakerEmbedding").size()).isEqualTo(192);
        assertThat(json.required("speakerEmbedding").get(0).floatValue()).isEqualTo(0.125f);
        assertThat(json.required("baselineVolume").doubleValue()).isEqualTo(-20.5);
        assertThat(json.required("baselinePitch").doubleValue()).isEqualTo(180.0);
        assertThat(json.required("modelVersion").asString()).isEqualTo("test-model");
        assertThat(json.required("baselineDetails").required("samples").intValue()).isEqualTo(3);
        assertThat(output.getAll()).doesNotContain(INTERNAL_KEY, "[0.125,", "test-model");
    }

    @Test
    void includesNullBaselineDetails() throws Exception {
        when(profiles.getVoiceProfile(7L)).thenReturn(featuresProfile(7L, 0.125f, null));
        var response = mvc.perform(get(FEATURES_URL).header("Authorization", "Bearer token-a")
                        .header("X-Internal-Key", INTERNAL_KEY))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(new JsonMapper().readTree(response.getContentAsString()).required("baselineDetails").isNull())
                .isTrue();
    }

    @Test
    void requiresFirebaseBeforeCheckingInternalKey() throws Exception {
        when(tokens.verifyIdToken("invalid")).thenThrow(new BadCredentialsException("invalid"));
        mvc.perform(get(FEATURES_URL)).andExpect(status().isUnauthorized());
        mvc.perform(get(FEATURES_URL).header("X-Internal-Key", INTERNAL_KEY)).andExpect(status().isUnauthorized());
        mvc.perform(get(FEATURES_URL).header("Authorization", "Bearer invalid").header("X-Internal-Key", INTERNAL_KEY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(users, profiles);
    }

    @Test
    void missingAndInvalidKeysHaveSameForbiddenResponse(CapturedOutput output) throws Exception {
        String incorrectKey = "synthetic-rejected-key";
        var missing = mvc.perform(get(FEATURES_URL).header("Authorization", "Bearer token-a"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andReturn().getResponse();
        mvc.perform(get(FEATURES_URL).header("Authorization", "Bearer token-a").header("X-Internal-Key", incorrectKey))
                .andExpect(status().isForbidden())
                .andExpect(content().string(missing.getContentAsString()));
        verifyNoInteractions(users, profiles);
        assertThat(output.getAll()).doesNotContain(incorrectKey, INTERNAL_KEY);
    }

    @Test
    void missingOwnProfileDoesNotFallBackToAnotherMember() throws Exception {
        when(profiles.getVoiceProfile(7L)).thenThrow(new VoiceProfileException(Reason.NOT_FOUND, "missing"));
        when(profiles.getVoiceProfile(8L)).thenReturn(featuresProfile(8L, 0.875f, null));
        mvc.perform(get(FEATURES_URL).param("userId", "8").header("Authorization", "Bearer token-a")
                        .header("X-Internal-Key", INTERNAL_KEY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.speakerEmbedding").doesNotExist());
    }

    @Test
    void featureReadFailureDoesNotExposeCredentialsOrEmbedding(CapturedOutput output) throws Exception {
        String sensitiveEmbedding = "[0.124567,0.842367]";
        when(profiles.getVoiceProfile(7L)).thenThrow(
                new VoiceProfileException(Reason.DATABASE_ERROR, INTERNAL_KEY + sensitiveEmbedding));
        mvc.perform(get(FEATURES_URL).header("Authorization", "Bearer token-a").header("X-Internal-Key", INTERNAL_KEY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString(INTERNAL_KEY))))
                .andExpect(content().string(not(containsString(sensitiveEmbedding))));
        assertThat(output.getAll()).doesNotContain(INTERNAL_KEY, sensitiveEmbedding);
    }

    @Test
    void writeMethodsAreOnlyExposedOnTheirOwnPaths() throws Exception {
        mvc.perform(request(HttpMethod.POST, URL).header("Authorization", "Bearer token-a"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/voice-profiles").header("Authorization", "Bearer token-a"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(profiles, registration);
    }

    @Test
    void authenticatedDeletionTargetsOnlyCallerAndClearsRegistrationStatus() throws Exception {
        when(profiles.getVoiceProfile(7L)).thenReturn(profile(7L, A_TIME));
        when(profiles.getVoiceProfile(8L)).thenReturn(profile(8L, B_TIME));
        doAnswer(invocation -> {
            org.mockito.Mockito.doThrow(new VoiceProfileException(Reason.NOT_FOUND, "missing"))
                    .when(profiles).getVoiceProfile(7L);
            return null;
        }).when(registration).delete(7L);

        mvc.perform(get(URL).header("Authorization", "Bearer token-a"))
                .andExpect(jsonPath("$.registered").value(true));
        mvc.perform(delete(URL).param("userId", "8").param("firebaseUid", "uid-b")
                        .header("Authorization", "Bearer token-a"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(registration).delete(7L);
        verifyNoMoreInteractions(registration);
        mvc.perform(get(URL).header("Authorization", "Bearer token-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registered").value(false));
        mvc.perform(get(URL).header("Authorization", "Bearer token-b"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registered").value(true));
    }

    @Test
    void deletionRequiresFirebaseAuthentication() throws Exception {
        mvc.perform(delete(URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(registration, users);
    }

    @Test
    void deletionOfUnregisteredProfileReturnsNotFound() throws Exception {
        doThrow(new VoiceProfileException(Reason.NOT_FOUND, "missing")).when(registration).delete(7L);
        mvc.perform(delete(URL).header("Authorization", "Bearer token-a"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updatesOnlyAuthenticatedMemberWithOptionalUrgentFile(boolean urgent) throws Exception {
        var request = multipart(HttpMethod.PUT, URL).part(analysis(192), audio("file"))
                .param("userId", "8").header("Authorization", "Bearer token-a")
                .header("X-Internal-Key", INTERNAL_KEY);
        if (urgent) request.part(audio("urgentFile"));
        mvc.perform(request).andExpect(status().isNoContent()).andExpect(content().string(""));
        var command = org.mockito.ArgumentCaptor.forClass(
                com.safepath.backend.voice.dto.VoiceProfileRegistration.class);
        verify(registration).updateExisting(eq(7L), command.capture());
        assertThat(command.getValue().urgentAudio() != null).isEqualTo(urgent);
    }

    @Test
    void updateRejectsMissingOrInvalidCredentialsBeforeCallingService() throws Exception {
        when(tokens.verifyIdToken("invalid")).thenThrow(new BadCredentialsException("invalid"));
        mvc.perform(multipart(HttpMethod.PUT, URL)).andExpect(status().isUnauthorized());
        mvc.perform(multipart(HttpMethod.PUT, URL).header("Authorization", "Bearer invalid")
                .header("X-Internal-Key", INTERNAL_KEY)).andExpect(status().isUnauthorized());
        mvc.perform(multipart(HttpMethod.PUT, URL).header("Authorization", "Bearer token-a"))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(HttpMethod.PUT, URL).header("Authorization", "Bearer token-a")
                .header("X-Internal-Key", "wrong")).andExpect(status().isForbidden());
        verifyNoInteractions(users, registration);
    }

    @Test
    void updateReturnsNotFoundWithoutExposingOriginalFailure(CapturedOutput output) throws Exception {
        doThrow(new VoiceProfileException(Reason.NOT_FOUND, "private-update-reference"))
                .when(registration).updateExisting(eq(7L), any());
        mvc.perform(updateRequest(analysis(192), audio("file")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("private-update-reference"))));
        assertThat(output.getAll()).doesNotContain("private-update-reference", INTERNAL_KEY);
    }

    @Test
    void updateReusesRegistrationValidationAndStorageErrorHandling(CapturedOutput output) throws Exception {
        mvc.perform(updateRequest(analysis(191), audio("file"))).andExpect(status().isBadRequest());
        mvc.perform(updateRequest(analysis(192), new MockPart("file", "empty.wav", new byte[0])))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(registration);
        doThrow(new VoiceAudioStorageException(VoiceAudioStorageException.Reason.INVALID_AUDIO))
                .doThrow(new IllegalStateException("private-update-storage"))
                .when(registration).updateExisting(eq(7L), any());
        mvc.perform(updateRequest(analysis(192), audio("file"))).andExpect(status().isBadRequest());
        mvc.perform(updateRequest(analysis(192), audio("file")))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("private-update-storage"))));
        assertThat(output.getAll()).doesNotContain("private-update-storage", INTERNAL_KEY);
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder updateRequest(MockPart... parts) {
        return multipart(HttpMethod.PUT, URL).part(parts).header("Authorization", "Bearer token-a")
                .header("X-Internal-Key", INTERNAL_KEY);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registersOnlyAuthenticatedMemberWithOptionalUrgentFile(boolean urgent) throws Exception {
        var request = multipart("/api/voice-profiles").part(analysis(192), audio("file"))
                .param("userId", "8").header("Authorization", "Bearer token-a")
                .header("X-Internal-Key", INTERNAL_KEY);
        if (urgent) request.part(audio("urgentFile"));
        mvc.perform(request).andExpect(status().isCreated()).andExpect(content().string(""));
        var command = org.mockito.ArgumentCaptor.forClass(
                com.safepath.backend.voice.dto.VoiceProfileRegistration.class);
        verify(registration).registerNew(eq(7L), command.capture());
        assertThat(command.getValue().urgentAudio() != null).isEqualTo(urgent);
    }

    @Test
    void registrationRequiresBothCredentialsBeforeParsing() throws Exception {
        when(tokens.verifyIdToken("invalid")).thenThrow(new BadCredentialsException("invalid"));
        mvc.perform(multipart("/api/voice-profiles").header("X-Internal-Key", INTERNAL_KEY))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/voice-profiles").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/voice-profiles").header("Authorization", "Bearer token-a"))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/voice-profiles").header("Authorization", "Bearer token-a")
                .header("X-Internal-Key", "wrong"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(users, registration);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "wav, audio/wav", "m4a, audio/mp4", "mp3, audio/mpeg"
    })
    void registrationPreservesAudioMetadataForStorageValidation(String extension, String contentType) throws Exception {
        String filename = "recording." + extension;
        var file = new MockPart("file", filename, new byte[] {1});
        file.getHeaders().setContentType(MediaType.parseMediaType(contentType));
        mvc.perform(registrationRequest(analysis(192), file)).andExpect(status().isCreated());
        var command = org.mockito.ArgumentCaptor.forClass(
                com.safepath.backend.voice.dto.VoiceProfileRegistration.class);
        verify(registration).registerNew(eq(7L), command.capture());
        assertThat(command.getValue().calmAudio().fileName()).isEqualTo(filename);
        assertThat(command.getValue().calmAudio().contentType()).isEqualTo(contentType);
    }

    @Test
    void registrationRejectsMissingPartsAndEmptyAudio() throws Exception {
        mvc.perform(registrationRequest(audio("file"))).andExpect(status().isBadRequest());
        mvc.perform(registrationRequest(analysis(192))).andExpect(status().isBadRequest());
        mvc.perform(registrationRequest(analysis(192), new MockPart("file", "empty.wav", new byte[0])))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(registration);
    }

    @ParameterizedTest
    @ValueSource(ints = {191, 193})
    void registrationRejectsWrongDimensions(int dimensions) throws Exception {
        mvc.perform(registrationRequest(analysis(dimensions), audio("file"))).andExpect(status().isBadRequest());
        verifyNoInteractions(registration);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{}", "{\"baselineVolume\":NaN}", "{\"baselinePitch\":1e999}"})
    void malformedOrIncompleteProfileReturnsBadRequest(String payload) throws Exception {
        mvc.perform(registrationRequest(jsonPart(payload), audio("file"))).andExpect(status().isBadRequest());
        verifyNoInteractions(registration);
    }

    @Test
    void duplicateRegistrationReturnsConflict() throws Exception {
        doThrow(new VoiceProfileException(Reason.ALREADY_EXISTS, "private reason"))
                .when(registration).registerNew(eq(7L), any());
        mvc.perform(registrationRequest(analysis(192), audio("file")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void invalidAudioAndStorageFailureReturnSanitizedErrors(CapturedOutput output) throws Exception {
        doThrow(new VoiceAudioStorageException(VoiceAudioStorageException.Reason.INVALID_AUDIO))
                .doThrow(new VoiceAudioStorageException(VoiceAudioStorageException.Reason.STORAGE_DISABLED))
                .doThrow(new IllegalStateException("private-storage-path"))
                .when(registration).registerNew(eq(7L), any());
        mvc.perform(registrationRequest(analysis(192), audio("file"))).andExpect(status().isBadRequest());
        mvc.perform(registrationRequest(analysis(192), audio("file")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("STORAGE_DISABLED"))));
        mvc.perform(registrationRequest(analysis(192), audio("file")))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("private-storage-path"))));
        assertThat(output.getAll()).contains("registration (IllegalStateException)")
                .doesNotContain("private-storage-path", INTERNAL_KEY);
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder registrationRequest(MockPart... parts) {
        return multipart("/api/voice-profiles").part(parts).header("Authorization", "Bearer token-a")
                .header("X-Internal-Key", INTERNAL_KEY);
    }

    private MockPart analysis(int dimensions) {
        return jsonPart("{\"speakerEmbedding\":[1" + ",0".repeat(dimensions - 1)
                + "],\"baselineVolume\":-20.5,\"baselinePitch\":180,\"modelVersion\":\"test\"}");
    }

    private MockPart jsonPart(String payload) {
        var part = new MockPart("profile", payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        part.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return part;
    }

    private MockPart audio(String name) {
        return new MockPart(name, "recording.wav", new byte[] {1});
    }

    private VoiceProfileResult profile(long userId, Instant time) {
        return new VoiceProfileResult(userId, new float[] {0.5f}, "private-audio-path",
                0.25, 145.0, "private-model", time, time, time,
                new JsonMapper().readTree("{\"internal\":true}"), "private-urgent-audio-path");
    }

    private VoiceProfileResult featuresProfile(long userId, float firstValue, String details) {
        var embedding = new float[192];
        embedding[0] = firstValue;
        return new VoiceProfileResult(userId, embedding, "private-audio-path", -20.5, 180.0, "test-model",
                A_TIME, A_TIME, A_TIME, details == null ? null : new JsonMapper().readTree(details),
                "private-urgent-audio-path");
    }
}
