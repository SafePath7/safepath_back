package com.safepath.backend.session.controller;

import com.safepath.backend.auth.FirebaseAuthenticationFilter;
import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.config.SecurityConfig;
import com.safepath.backend.session.exception.SafeReturnSessionExceptionHandler;
import com.safepath.backend.session.service.SafeReturnSessionService;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import com.safepath.backend.session.store.SessionChannelStore;
import com.safepath.backend.websocket.InMemoryChannelRegistry;
import com.safepath.backend.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SafeReturnSessionControllerTest {
	@Test
	void selectedRouteIsAvailableToOwnerAndLocationAuthorizedGuardian() throws Exception {
		String body = """
				{"guardianIds":[2],"selectedRoute":{"type":"SAFEST","coordinates":[{"latitude":35.0,"longitude":128.0},{"latitude":35.001,"longitude":128.0}],"distanceMeters":120,"durationSeconds":90,"safetyScore":65,"destinationName":"CU 대구신당점"}}
				""";
		MvcResult created = mockMvc.perform(post("/api/safe-return/sessions")
				.header("Authorization", bearer(USER_1_TOKEN)).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn();
		String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(get("/api/safe-return/sessions/" + id + "/status").header("Authorization", bearer(USER_1_TOKEN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.selectedRoute.type").value("SAFEST"))
				.andExpect(jsonPath("$.selectedRoute.destinationName").value("CU 대구신당점"));
		mockMvc.perform(get("/api/guardian/monitoring/sessions/" + id + "/route").header("Authorization", bearer("guardian-2")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.coordinates[1].latitude").value(35.001))
				.andExpect(jsonPath("$.destinationName").value("CU 대구신당점"));
		mockMvc.perform(get("/api/guardian/monitoring/sessions/" + id + "/route").header("Authorization", bearer("guardian-3")))
				.andExpect(status().isForbidden());
		var permissions = context.getBean(com.safepath.backend.session.service.GuardianPermissionService.class);
		when(permissions.hasPermission(42L, 2L, com.safepath.backend.session.model.MonitoringPermissionType.LOCATION)).thenReturn(false);
		mockMvc.perform(get("/api/guardian/monitoring/sessions/" + id + "/route").header("Authorization", bearer("guardian-2")))
				.andExpect(status().isForbidden());
	}

	@Test
	void rejectsInvalidSelectedRouteBeforeStartingSession() throws Exception {
		mockMvc.perform(post("/api/safe-return/sessions").header("Authorization", bearer(USER_1_TOKEN))
				.contentType(MediaType.APPLICATION_JSON).content("{\"guardianIds\":[2],\"selectedRoute\":{\"type\":\"SAFEST\",\"coordinates\":[{\"latitude\":91,\"longitude\":128}],\"distanceMeters\":10,\"durationSeconds\":10}}"))
				.andExpect(status().isBadRequest());
		assertTrue(context.getBean(ActiveSessionStore.class).findByUserId(42L).isEmpty());
	}
	@Test
	void channelCreationFailureReturnsExistingInternalErrorInsteadOfConflict() throws Exception {
		doThrow(new IllegalStateException("Channel unavailable"))
				.when(context.getBean(InMemoryChannelRegistry.class)).open(42L, java.util.Set.of(2L, 3L));
		mockMvc.perform(post("/api/safe-return/sessions")
				.header("Authorization", bearer(USER_1_TOKEN)).contentType(MediaType.APPLICATION_JSON).content("{\"guardianIds\":[2,3]}"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
		assertTrue(context.getBean(ActiveSessionStore.class).findByUserId(42L).isEmpty());
	}

	private static final String USER_1_UID = "firebase-uid-1";
	private static final String USER_2_UID = "firebase-uid-2";
	private static final String USER_1_TOKEN = "valid-token-1";
	private static final String USER_2_TOKEN = "valid-token-2";

	private AnnotationConfigWebApplicationContext context;
	private MockMvc mockMvc;
	private FirebaseTokenService tokenService;
	private UserService userService;
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		context = new AnnotationConfigWebApplicationContext();
		context.setServletContext(new MockServletContext());
		((StandardEnvironment) context.getEnvironment()).setActiveProfiles("test");
		context.register(TestWebConfiguration.class);
		context.refresh();
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

		tokenService = context.getBean(FirebaseTokenService.class);
		userService = context.getBean(UserService.class);
		objectMapper = context.getBean(ObjectMapper.class);
		when(tokenService.verifyIdToken(USER_1_TOKEN)).thenReturn(USER_1_UID);
		when(tokenService.verifyIdToken(USER_2_TOKEN)).thenReturn(USER_2_UID);
		when(userService.findUserIdByFirebaseUid(USER_1_UID)).thenReturn(Optional.of(42L));
		when(userService.findUserIdByFirebaseUid(USER_2_UID)).thenReturn(Optional.of(43L));
		var permissions = context.getBean(com.safepath.backend.session.service.GuardianPermissionService.class);
		for (Long guardianId : java.util.List.of(2L, 3L)) {
			when(permissions.hasAcceptedRelationship(42L, guardianId)).thenReturn(true);
			when(permissions.hasPermission(42L, guardianId,
					com.safepath.backend.session.model.MonitoringPermissionType.LOCATION)).thenReturn(true);
			when(permissions.hasPermission(42L, guardianId,
					com.safepath.backend.session.model.MonitoringPermissionType.STATUS)).thenReturn(true);
			when(tokenService.verifyIdToken("guardian-" + guardianId)).thenReturn("guardian-uid-" + guardianId);
			when(userService.findUserIdByFirebaseUid("guardian-uid-" + guardianId)).thenReturn(Optional.of(guardianId));
		}
	}

	@AfterEach
	void tearDown() {
		context.close();
	}

	@Test
	void startsSessionUsingDatabaseIdMappedFromFirebaseUid() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/safe-return/sessions")
						.header("Authorization", bearer(USER_1_TOKEN)).contentType(MediaType.APPLICATION_JSON).content("{\"guardianIds\":[2,3]}"))
				.andExpect(status().isCreated())
				.andReturn();

		JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
		UUID sessionId = UUID.fromString(response.get("id").asText());
		assertEquals(3, response.size());
		assertTrue(response.get("startedAt").isTextual());
		String channelId = response.get("channelId").asText();
		assertEquals(context.getBean(SessionChannelStore.class).findBySessionId(sessionId).orElseThrow(), channelId);
		assertTrue(context.getBean(InMemoryChannelRegistry.class).exists(channelId));
		assertEquals(java.util.Set.of(2L, 3L), context.getBean(SelectedGuardianStore.class).findBySessionId(sessionId));
		assertFalse(response.has("userId"));
		assertFalse(response.has("status"));
		verify(userService).findUserIdByFirebaseUid(USER_1_UID);

		mockMvc.perform(get(statusPath(sessionId))
						.header("Authorization", bearer(USER_1_TOKEN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(sessionId.toString()));
	}

	@Test
	void returnsNoContentWhenOwnerEndsSession() throws Exception {
		UUID sessionId = startSession(USER_1_TOKEN);
		String channelId = context.getBean(SessionChannelStore.class).findBySessionId(sessionId).orElseThrow();

		mockMvc.perform(patch(endPath(sessionId))
						.header("Authorization", bearer(USER_1_TOKEN)))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
		assertFalse(context.getBean(InMemoryChannelRegistry.class).exists(channelId));
		assertTrue(context.getBean(SessionChannelStore.class).findBySessionId(sessionId).isEmpty());

		mockMvc.perform(get(statusPath(sessionId))
						.header("Authorization", bearer(USER_1_TOKEN)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
	}

	@Test
	void hidesSessionFromAnotherRegisteredFirebaseUser() throws Exception {
		UUID sessionId = startSession(USER_1_TOKEN);

		mockMvc.perform(get(statusPath(sessionId))
						.header("Authorization", bearer(USER_2_TOKEN)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
		mockMvc.perform(patch(endPath(sessionId))
						.header("Authorization", bearer(USER_2_TOKEN)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));

		mockMvc.perform(get(statusPath(sessionId))
						.header("Authorization", bearer(USER_1_TOKEN)))
				.andExpect(status().isOk());
	}

	@Test
	void rejectsUnregisteredFirebaseUidWithoutCreatingSession() throws Exception {
		when(userService.findUserIdByFirebaseUid("unregistered-uid")).thenReturn(Optional.empty());
		when(tokenService.verifyIdToken("unregistered-token")).thenReturn("unregistered-uid");

		mockMvc.perform(post("/api/safe-return/sessions")
						.header("Authorization", bearer("unregistered-token")).contentType(MediaType.APPLICATION_JSON).content("{\"guardianIds\":[2,3]}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("USER_NOT_REGISTERED"));

		verify(userService).findUserIdByFirebaseUid("unregistered-uid");
	}

	@Test
	void rejectsRequestsWithoutFirebaseAuthentication() throws Exception {
		mockMvc.perform(post("/api/safe-return/sessions"))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(userService);
	}

	@Test
	void rejectsInvalidFirebaseTokenWithoutLookingUpUser() throws Exception {
		when(tokenService.verifyIdToken("invalid-token"))
				.thenThrow(new BadCredentialsException("Invalid Firebase ID token"));

		mockMvc.perform(post("/api/safe-return/sessions")
						.header("Authorization", bearer("invalid-token")))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(userService);
	}

	@Test
	void rejectsDuplicateSessionStartWithConflict() throws Exception {
		startSession(USER_1_TOKEN);

		mockMvc.perform(post("/api/safe-return/sessions")
						.header("Authorization", bearer(USER_1_TOKEN)).contentType(MediaType.APPLICATION_JSON).content("{\"guardianIds\":[2,3]}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ACTIVE_SESSION_CONFLICT"));
	}

	@Test
	void rejectsMalformedSessionUuid() throws Exception {
		mockMvc.perform(get("/api/safe-return/sessions/not-a-uuid/status")
						.header("Authorization", bearer(USER_1_TOKEN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SESSION_ID"));
	}

	@Test
	void ignoresClientSuppliedUserIds() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/safe-return/sessions")
						.header("Authorization", bearer(USER_1_TOKEN))
						.header("X-User-Id", "43")
						.param("userId", "43")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":43,\"guardianIds\":[2,3]}"))
				.andExpect(status().isCreated())
				.andReturn();
		UUID sessionId = UUID.fromString(
				objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

		mockMvc.perform(get(statusPath(sessionId))
						.header("Authorization", bearer(USER_2_TOKEN)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get(statusPath(sessionId))
						.header("Authorization", bearer(USER_1_TOKEN)))
				.andExpect(status().isOk());
	}

	@Test
	void rejectsMissingEmptyAndInvalidGuardianLists() throws Exception {
		for (String body : java.util.List.of("{}", "{\"guardianIds\":[]}", "{\"guardianIds\":[null]}",
				"{\"guardianIds\":[-1]}", "{\"guardianIds\":\"invalid\"}")) {
			mockMvc.perform(post("/api/safe-return/sessions").header("Authorization", bearer(USER_1_TOKEN))
					.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
		mockMvc.perform(post("/api/safe-return/sessions").header("Authorization", bearer(USER_1_TOKEN)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		assertTrue(context.getBean(ActiveSessionStore.class).findByUserId(42L).isEmpty());
	}

	@Test
	void rejectsUnauthorizedGuardianAndSelfSelectionWithoutCreatingResources() throws Exception {
		for (String body : java.util.List.of("{\"guardianIds\":[2,999]}", "{\"guardianIds\":[42]}")) {
			mockMvc.perform(post("/api/safe-return/sessions").header("Authorization", bearer(USER_1_TOKEN))
					.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("GUARDIAN_SELECTION_DENIED"))
					.andExpect(jsonPath("$.message").value("Check the selected guardians and their location permissions."));
		}
		assertTrue(context.getBean(ActiveSessionStore.class).findByUserId(42L).isEmpty());
	}

	@Test
	void rejectsAcceptedGuardianWithoutLocationPermissionWithSelectionError() throws Exception {
		var permissions = context.getBean(com.safepath.backend.session.service.GuardianPermissionService.class);
		when(permissions.hasPermission(42L, 2L,
				com.safepath.backend.session.model.MonitoringPermissionType.LOCATION)).thenReturn(false);
		mockMvc.perform(post("/api/safe-return/sessions").header("Authorization", bearer(USER_1_TOKEN))
				.contentType(MediaType.APPLICATION_JSON).content("{\"guardianIds\":[2]}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("GUARDIAN_SELECTION_DENIED"));
		assertTrue(context.getBean(ActiveSessionStore.class).findByUserId(42L).isEmpty());
	}

	@Test
	void selectedGuardiansReadStatusAndCannotReadAfterEnd() throws Exception {
		UUID sessionId = startSession(USER_1_TOKEN);
		String base = "/api/guardian/monitoring/sessions/" + sessionId;
		for (String token : java.util.List.of("guardian-2", "guardian-3")) {
			mockMvc.perform(get(base + "/status").header("Authorization", bearer(token)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(sessionId.toString()))
					.andExpect(jsonPath("$.channelId").doesNotExist());
		}
		context.getBean(SafeReturnSessionService.class).endSession(sessionId, 42L);
		for (String suffix : java.util.List.of("/status")) {
			mockMvc.perform(get(base + suffix).header("Authorization", bearer("guardian-2")))
					.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
		}
	}

	@Test
	void monitoringRequiresAuthenticatedSelectedGuardianAndLocationPermission() throws Exception {
		UUID sessionId = startSession(USER_1_TOKEN);
		String base = "/api/guardian/monitoring/sessions/" + sessionId;
		var permissions = context.getBean(com.safepath.backend.session.service.GuardianPermissionService.class);
		when(permissions.hasAcceptedRelationship(42L, 43L)).thenReturn(true);
		when(permissions.hasPermission(42L, 43L,
				com.safepath.backend.session.model.MonitoringPermissionType.STATUS)).thenReturn(true);
		when(permissions.hasPermission(42L, 43L,
				com.safepath.backend.session.model.MonitoringPermissionType.LOCATION)).thenReturn(true);
		when(permissions.hasPermission(42L, 2L,
				com.safepath.backend.session.model.MonitoringPermissionType.LOCATION)).thenReturn(false);
		mockMvc.perform(get(base + "/channel").header("Authorization", bearer("guardian-2")))
				.andExpect(status().isOk());
		mockMvc.perform(get(base + "/status").header("Authorization", bearer("guardian-2")))
				.andExpect(status().isForbidden());
		when(permissions.hasPermission(42L, 3L,
				com.safepath.backend.session.model.MonitoringPermissionType.STATUS)).thenReturn(false);
		mockMvc.perform(get(base + "/status").header("Authorization", bearer("guardian-3")))
				.andExpect(status().isOk());
		mockMvc.perform(get(base + "/channel").header("Authorization", bearer("guardian-3")))
				.andExpect(status().isOk());
		for (String suffix : java.util.List.of("/status", "/channel")) {
			mockMvc.perform(get(base + suffix)).andExpect(status().isUnauthorized());
			mockMvc.perform(get(base + suffix).header("Authorization", bearer(USER_1_TOKEN)))
					.andExpect(status().isForbidden());
			mockMvc.perform(get(base + suffix).header("Authorization", bearer(USER_2_TOKEN))
					.param("guardianId", "2")).andExpect(status().isForbidden());
		}
	}

	private UUID startSession(String token) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/safe-return/sessions")
						.header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content("{\"guardianIds\":[2,3]}"))
				.andExpect(status().isCreated())
				.andReturn();
		return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
	}

	@Test
	void selectedGuardiansReceiveOnlyTheMappedChannelId() throws Exception {
		UUID sessionId = startSession(USER_1_TOKEN);
		String channelId = context.getBean(SessionChannelStore.class).findBySessionId(sessionId).orElseThrow();
		for (String token : java.util.List.of("guardian-2", "guardian-3")) {
			MvcResult result = mockMvc.perform(get(channelPath(sessionId)).header("Authorization", bearer(token)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.channelId").value(channelId)).andReturn();
			assertEquals(1, objectMapper.readTree(result.getResponse().getContentAsString()).size());
		}
	}

	@Test
	void channelLookupRejectsFirebaseAccountWithoutLocalRegistration() throws Exception {
		UUID sessionId = startSession(USER_1_TOKEN);
		when(userService.findUserIdByFirebaseUid("guardian-uid-2")).thenReturn(Optional.empty());
		mockMvc.perform(get(channelPath(sessionId)).header("Authorization", bearer("guardian-2")))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.channelId").doesNotExist());
	}

	@Test
	void channelLookupKeepsSelectedGuardianAfterRelationshipRevocation() throws Exception {
		UUID sessionId = startSession(USER_1_TOKEN);
		var permissions = context.getBean(com.safepath.backend.session.service.GuardianPermissionService.class);
		when(permissions.hasAcceptedRelationship(42L, 2L)).thenReturn(false);
		mockMvc.perform(get(channelPath(sessionId)).header("Authorization", bearer("guardian-2")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.channelId").isNotEmpty());
	}

	@Test
	void channelLookupKeepsSelectedGuardianAfterLocationPermissionRevocation() throws Exception {
		UUID sessionId = startSession(USER_1_TOKEN);
		var permissions = context.getBean(com.safepath.backend.session.service.GuardianPermissionService.class);
		when(permissions.hasPermission(42L, 2L,
				com.safepath.backend.session.model.MonitoringPermissionType.LOCATION)).thenReturn(false);
		mockMvc.perform(get(channelPath(sessionId)).header("Authorization", bearer("guardian-2")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.channelId").isNotEmpty());
	}

	@Test
	void acceptedButUnselectedGuardianCannotSpoofSelectedGuardianId() throws Exception {
		UUID sessionId = startSession(USER_1_TOKEN);
		var permissions = context.getBean(com.safepath.backend.session.service.GuardianPermissionService.class);
		when(permissions.hasAcceptedRelationship(42L, 43L)).thenReturn(true);
		when(permissions.hasPermission(42L, 43L,
				com.safepath.backend.session.model.MonitoringPermissionType.LOCATION)).thenReturn(true);
		mockMvc.perform(get(channelPath(sessionId)).header("Authorization", bearer(USER_2_TOKEN))
				.param("guardianId", "2").header("X-User-Id", "2"))
				.andExpect(status().isForbidden());
	}

	@Test
	void guardianChannelEndpointRejectsOwnerWhileExistingOwnerStatusStillWorks() throws Exception {
		UUID sessionId = startSession(USER_1_TOKEN);
		mockMvc.perform(get(channelPath(sessionId)).header("Authorization", bearer(USER_1_TOKEN)))
				.andExpect(status().isForbidden());
		mockMvc.perform(get(statusPath(sessionId)).header("Authorization", bearer(USER_1_TOKEN)))
				.andExpect(status().isOk());
	}

	@Test
	void unknownAndEndedSessionsCannotRevealChannels() throws Exception {
		mockMvc.perform(get(channelPath(UUID.randomUUID())).header("Authorization", bearer("guardian-2")))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
		UUID sessionId = startSession(USER_1_TOKEN);
		mockMvc.perform(patch(endPath(sessionId)).header("Authorization", bearer(USER_1_TOKEN)))
				.andExpect(status().isNoContent());
		mockMvc.perform(get(channelPath(sessionId)).header("Authorization", bearer("guardian-2")))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.channelId").doesNotExist());
	}

	@Test
	void channelLookupRejectsMissingMapping() throws Exception {
		UUID sessionId = startSession(USER_1_TOKEN);
		context.getBean(SessionChannelStore.class).remove(sessionId);
		mockMvc.perform(get(channelPath(sessionId)).header("Authorization", bearer("guardian-2")))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.channelId").doesNotExist());
	}

	@Test
	void channelLookupRejectsMismatchedReverseMapping() throws Exception {
		UUID sessionId = startSession(USER_1_TOKEN);
		SessionChannelStore mappings = context.getBean(SessionChannelStore.class);
		String channelId = mappings.findBySessionId(sessionId).orElseThrow();
		when(mappings.findSessionIdByChannelId(channelId)).thenReturn(Optional.of(UUID.randomUUID()));
		mockMvc.perform(get(channelPath(sessionId)).header("Authorization", bearer("guardian-2")))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.channelId").doesNotExist());
	}

	@Test
	void channelLookupRequiresValidFirebaseAuthentication() throws Exception {
		UUID sessionId = startSession(USER_1_TOKEN);
		mockMvc.perform(get(channelPath(sessionId))).andExpect(status().isUnauthorized());
		when(tokenService.verifyIdToken("invalid-channel-token"))
				.thenThrow(new BadCredentialsException("Invalid Firebase ID token"));
		mockMvc.perform(get(channelPath(sessionId)).header("Authorization", bearer("invalid-channel-token")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void channelLookupDoesNotDependOnCurrentGuardianPermissionLookup() throws Exception {
		UUID sessionId = startSession(USER_1_TOKEN);
		var permissions = context.getBean(com.safepath.backend.session.service.GuardianPermissionService.class);
		when(permissions.hasPermission(42L, 2L,
				com.safepath.backend.session.model.MonitoringPermissionType.LOCATION))
				.thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Lookup unavailable"));
		mockMvc.perform(get(channelPath(sessionId)).header("Authorization", bearer("guardian-2")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.channelId").isNotEmpty());
	}

	@Test
	void channelLookupRejectsMalformedSessionId() throws Exception {
		mockMvc.perform(get("/api/guardian/monitoring/sessions/not-a-uuid/channel")
				.header("Authorization", bearer("guardian-2")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SESSION_ID"));
	}

	private static String channelPath(UUID sessionId) {
		return "/api/guardian/monitoring/sessions/" + sessionId + "/channel";
	}

	private static String statusPath(UUID sessionId) {
		return "/api/safe-return/sessions/" + sessionId + "/status";
	}

	private static String endPath(UUID sessionId) {
		return "/api/safe-return/sessions/" + sessionId + "/end";
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	@Configuration(proxyBeanMethods = false)
	@EnableWebMvc
	@EnableWebSecurity
	@Import({
			SecurityConfig.class,
			FirebaseAuthenticationFilter.class,
			SafeReturnSessionController.class,
            GuardianMonitoringController.class,
            com.safepath.backend.session.service.GuardianMonitoringService.class,
            com.safepath.backend.session.service.GuardianMonitoringQueryService.class,
			SafeReturnSessionExceptionHandler.class,
			ActiveSessionStore.class,
			SelectedGuardianStore.class,
			SafeReturnSessionService.class,
			com.safepath.backend.session.service.GuardianSelectionService.class
	})
	static class TestWebConfiguration {
		@Bean
		com.safepath.backend.notification.NotificationService notificationService() {
			return mock(com.safepath.backend.notification.NotificationService.class);
		}
		@Bean
		SessionChannelStore sessionChannelStore() {
			return spy(new SessionChannelStore());
		}

		@Bean
		com.safepath.backend.session.service.GuardianPermissionService guardianPermissionService() {
			return mock(com.safepath.backend.session.service.GuardianPermissionService.class);
		}
		@Bean
		InMemoryChannelRegistry channelRegistry() {
			return spy(new InMemoryChannelRegistry());
		}

		@Bean
		FirebaseTokenService firebaseTokenService() {
			return mock(FirebaseTokenService.class);
		}

		@Bean
		UserService userService() {
			return mock(UserService.class);
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}
	}
}
