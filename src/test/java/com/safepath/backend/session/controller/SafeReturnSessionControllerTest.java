package com.safepath.backend.session.controller;

import com.safepath.backend.auth.FirebaseAuthenticationFilter;
import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.config.SecurityConfig;
import com.safepath.backend.session.exception.SafeReturnSessionExceptionHandler;
import com.safepath.backend.session.service.SafeReturnSessionService;
import com.safepath.backend.session.store.ActiveSessionStore;
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
	}

	@AfterEach
	void tearDown() {
		context.close();
	}

	@Test
	void startsSessionUsingDatabaseIdMappedFromFirebaseUid() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/safe-return/sessions")
						.header("Authorization", bearer(USER_1_TOKEN)))
				.andExpect(status().isCreated())
				.andReturn();

		JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
		UUID sessionId = UUID.fromString(response.get("id").asText());
		assertEquals(2, response.size());
		assertTrue(response.get("startedAt").isTextual());
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

		mockMvc.perform(patch(endPath(sessionId))
						.header("Authorization", bearer(USER_1_TOKEN)))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

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
						.header("Authorization", bearer("unregistered-token")))
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
						.header("Authorization", bearer(USER_1_TOKEN)))
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
						.content("{\"userId\":43}"))
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

	private UUID startSession(String token) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/safe-return/sessions")
						.header("Authorization", bearer(token)))
				.andExpect(status().isCreated())
				.andReturn();
		return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
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
			SafeReturnSessionExceptionHandler.class,
			ActiveSessionStore.class,
			SafeReturnSessionService.class
	})
	static class TestWebConfiguration {

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
