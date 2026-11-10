package com.safepath.backend.security.local;

import com.safepath.backend.session.controller.SafeReturnSessionController;
import com.safepath.backend.session.exception.SafeReturnSessionExceptionHandler;
import com.safepath.backend.session.service.SafeReturnSessionService;
import com.safepath.backend.session.store.ActiveSessionStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LocalTestSessionControllerTest {

	private static final String USER_1_PASSWORD = "synthetic-test-password-one";
	private static final String USER_2_PASSWORD = "synthetic-test-password-two";

	private AnnotationConfigWebApplicationContext context;
	private MockMvc mockMvc;
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		context = new AnnotationConfigWebApplicationContext();
		context.setServletContext(new MockServletContext());
		((StandardEnvironment) context.getEnvironment()).setActiveProfiles("local", "local-test-auth");
		context.register(TestWebConfiguration.class);
		context.refresh();
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		objectMapper = context.getBean(ObjectMapper.class);
	}

	@AfterEach
	void tearDown() {
		context.close();
	}

	@Test
	void startsSessionAndReturnsOnlyIdAndStartedAt() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/safe-return/sessions")
						.header("Authorization", basic(LocalTestUserDetailsConfiguration.USERNAME_1, USER_1_PASSWORD)))
				.andExpect(status().isCreated())
				.andReturn();

		JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
		assertTrue(response.get("id").isTextual());
		UUID.fromString(response.get("id").asText());
		assertTrue(response.get("startedAt").isTextual());
		assertEquals(2, response.size());
		assertFalse(response.has("userId"));
		assertFalse(response.has("status"));
	}

	@Test
	void retrievesOwnSessionStatus() throws Exception {
		UUID sessionId = startSession(LocalTestUserDetailsConfiguration.USERNAME_1, USER_1_PASSWORD);
		MvcResult result = mockMvc.perform(get(statusPath(sessionId))
						.header("Authorization", basic(LocalTestUserDetailsConfiguration.USERNAME_1, USER_1_PASSWORD)))
				.andExpect(status().isOk())
				.andReturn();

		JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
		assertEquals(sessionId.toString(), response.get("id").asText());
		assertTrue(response.get("startedAt").isTextual());
		assertEquals(2, response.size());
		assertFalse(response.has("userId"));
	}

	@Test
	void endsOwnSessionWithNoContent() throws Exception {
		UUID sessionId = startSession(LocalTestUserDetailsConfiguration.USERNAME_1, USER_1_PASSWORD);

		mockMvc.perform(patch(endPath(sessionId))
						.header("Authorization", basic(LocalTestUserDetailsConfiguration.USERNAME_1, USER_1_PASSWORD)))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
	}

	@Test
	void rejectsRequestsWithoutAuthentication() throws Exception {
		mockMvc.perform(post("/api/safe-return/sessions"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsInvalidBasicCredentials() throws Exception {
		mockMvc.perform(post("/api/safe-return/sessions")
						.header("Authorization", basic(LocalTestUserDetailsConfiguration.USERNAME_1, "wrong-password")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsDuplicateSessionStartWithConflict() throws Exception {
		startSession(LocalTestUserDetailsConfiguration.USERNAME_1, USER_1_PASSWORD);

		mockMvc.perform(post("/api/safe-return/sessions")
						.header("Authorization", basic(LocalTestUserDetailsConfiguration.USERNAME_1, USER_1_PASSWORD)))
				.andExpect(status().isConflict());
	}

	@Test
	void returnsNotFoundForMissingSession() throws Exception {
		mockMvc.perform(get(statusPath(UUID.randomUUID()))
						.header("Authorization", basic(LocalTestUserDetailsConfiguration.USERNAME_1, USER_1_PASSWORD)))
				.andExpect(status().isNotFound());
	}

	@Test
	void hidesSessionsFromOtherUsersForStatusAndEnd() throws Exception {
		UUID sessionId = startSession(LocalTestUserDetailsConfiguration.USERNAME_1, USER_1_PASSWORD);
		String user2Authorization = basic(LocalTestUserDetailsConfiguration.USERNAME_2, USER_2_PASSWORD);

		mockMvc.perform(get(statusPath(sessionId)).header("Authorization", user2Authorization))
				.andExpect(status().isNotFound());
		mockMvc.perform(patch(endPath(sessionId)).header("Authorization", user2Authorization))
				.andExpect(status().isNotFound());

		mockMvc.perform(get(statusPath(sessionId))
						.header("Authorization", basic(LocalTestUserDetailsConfiguration.USERNAME_1, USER_1_PASSWORD)))
				.andExpect(status().isOk());
	}

	@Test
	void rejectsMalformedSessionUuidWithBadRequest() throws Exception {
		mockMvc.perform(get("/api/safe-return/sessions/not-a-uuid/status")
						.header("Authorization", basic(LocalTestUserDetailsConfiguration.USERNAME_1, USER_1_PASSWORD)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void endedSessionCannotBeReadOrEndedAgain() throws Exception {
		UUID sessionId = startSession(LocalTestUserDetailsConfiguration.USERNAME_1, USER_1_PASSWORD);
		String authorization = basic(LocalTestUserDetailsConfiguration.USERNAME_1, USER_1_PASSWORD);

		mockMvc.perform(patch(endPath(sessionId)).header("Authorization", authorization))
				.andExpect(status().isNoContent());
		mockMvc.perform(get(statusPath(sessionId)).header("Authorization", authorization))
				.andExpect(status().isNotFound());
		mockMvc.perform(patch(endPath(sessionId)).header("Authorization", authorization))
				.andExpect(status().isNotFound());
	}

	@Test
	void ignoresClaimedUserIdsInBodyQueryAndHeaders() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/safe-return/sessions")
						.header("Authorization", basic(LocalTestUserDetailsConfiguration.USERNAME_1, USER_1_PASSWORD))
						.header("X-User-Id", LocalTestUserDetailsConfiguration.USER_ID_2.toString())
						.param("userId", LocalTestUserDetailsConfiguration.USER_ID_2.toString())
						.contentType("application/json")
						.content("{\"userId\":9000000002}"))
				.andExpect(status().isCreated())
				.andReturn();
		UUID sessionId = UUID.fromString(
				objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

		mockMvc.perform(get(statusPath(sessionId))
						.header("Authorization", basic(LocalTestUserDetailsConfiguration.USERNAME_2, USER_2_PASSWORD)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get(statusPath(sessionId))
						.header("Authorization", basic(LocalTestUserDetailsConfiguration.USERNAME_1, USER_1_PASSWORD)))
				.andExpect(status().isOk());
	}

	@Test
	void rejectsAuthenticatedPrincipalOfUnexpectedType() throws Exception {
		RequestPostProcessor alternatePrincipal = org.springframework.security.test.web.servlet.request
				.SecurityMockMvcRequestPostProcessors.authentication(
						UsernamePasswordAuthenticationToken.authenticated(
								"not-a-local-test-principal",
								null,
								List.of(new SimpleGrantedAuthority("ROLE_USER"))));

		mockMvc.perform(post("/api/safe-return/sessions").with(alternatePrincipal))
				.andExpect(status().isUnauthorized());
	}

	private UUID startSession(String username, String password) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/safe-return/sessions")
						.header("Authorization", basic(username, password)))
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

	private static String basic(String username, String password) {
		String credentials = username + ":" + password;
		return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableWebMvc
	@Import({
			LocalTestSecurityConfiguration.class,
			SafeReturnSessionController.class,
			SafeReturnSessionExceptionHandler.class,
			ActiveSessionStore.class,
			SafeReturnSessionService.class
	})
	static class TestWebConfiguration {

		@Bean
		UserDetailsService localTestUserDetailsService(PasswordEncoder passwordEncoder) {
			return LocalTestUserDetailsConfiguration.createUserDetailsService(
					Map.of(
							LocalTestAuthStartupGuard.USER_1_PASSWORD_ENV, USER_1_PASSWORD,
							LocalTestAuthStartupGuard.USER_2_PASSWORD_ENV, USER_2_PASSWORD),
					passwordEncoder);
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}
	}
}
